package com.example.notifications;

import static com.example.notifications.Models.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class CoreTest {
    private final ObjectMapper json = new ObjectMapper();

    private EventInput event(String payload) throws Exception {
        return new EventInput("order.created", "checkout", json.readTree(payload));
    }

    private Rules rule(String path, String value) throws Exception {
        return new Rules(null, null, List.of(new Condition(path, "eq", json.readTree(value))));
    }

    @Test
    void filtersCombineTypeSourceAndNestedConditions() throws Exception {
        var rules =
                new Rules(
                        "order.created",
                        "checkout",
                        List.of(new Condition("/customer/tier", "eq", json.readTree("\"gold\""))));
        assertTrue(Filters.matches(rules, event("{\"customer\":{\"tier\":\"gold\"}}")));
        assertFalse(Filters.matches(rules, event("{\"customer\":{\"tier\":\"silver\"}}")));
        assertFalse(
                Filters.matches(rules, new EventInput("other", "checkout", json.readTree("{}"))));
        assertFalse(
                Filters.matches(
                        rules, new EventInput("order.created", "other", json.readTree("{}"))));
    }

    @Test
    void missingNullAndBooleanRemainDistinct() throws Exception {
        assertTrue(Filters.matches(rule("/value", "null"), event("{\"value\":null}")));
        assertFalse(Filters.matches(rule("/value", "null"), event("{}")));
        assertFalse(Filters.matches(rule("/value", "true"), event("{\"value\":1}")));
        assertTrue(Filters.matches(rule("/value", "1.0"), event("{\"value\":1}")));
        assertTrue(Filters.matches(Rules.all(), event("null")));
    }

    @Test
    void pointersSupportArraysAndEscapes() throws Exception {
        assertTrue(Filters.matches(rule("/a~1b/~0key/0", "3"), event("{\"a/b\":{\"~key\":[3]}}")));
        assertTrue(Filters.matches(rule("", "[1,2]"), event("[1,2]")));
        assertThrows(IllegalArgumentException.class, () -> Filters.validate(rule("bad", "1")));
        assertThrows(IllegalArgumentException.class, () -> Filters.validate(rule("/bad~2", "1")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Filters.validate(
                                new Rules(
                                        null,
                                        null,
                                        List.of(new Condition("", "gt", NullNode.instance)))));
    }

    @Test
    void retriesAreBoundedAndClassified() {
        var policy = new RetryPolicy(5, 1000, 5000, () -> 0);
        assertTrue(policy.retryable(null));
        assertTrue(policy.retryable(408));
        assertTrue(policy.retryable(429));
        assertTrue(policy.retryable(503));
        assertFalse(policy.retryable(400));
        assertFalse(policy.retryable(302));
        assertEquals(500, policy.delayMillis(1));
        assertEquals(2500, policy.delayMillis(20));
        var upper = new RetryPolicy(5, 1000, 5000, () -> 0.999999);
        assertTrue(upper.delayMillis(20) <= 5000);
        assertTrue(upper.delayMillis(1) >= 500);
    }

    @Test
    void destinationPolicyRequiresApprovedOrigins() {
        var config = new ServiceConfiguration();
        config.allowedWebhookOrigins = List.of("http://127.0.0.1:9090", "https://8.8.8.8");
        var policy = new WebhookPolicy(config);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate("http://127.0.0.1:9090/test"));
        assertThrows(
                IllegalArgumentException.class, () -> policy.validate("https://example.com/test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate("https://8.8.8.8@127.0.0.1/test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate("https://8.8.8.8/test#fragment"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://8.8.4.4/test"));
        assertNotNull(policy.validate("https://8.8.8.8/test"));
        config.allowLocalWebhooks = true;
        assertNotNull(new WebhookPolicy(config).validate("http://127.0.0.1:9090/test"));
    }

    @Test
    void rateLimitUsesConfiguredTenantWindow() {
        var config = new ServiceConfiguration();
        config.requestsPerMinute = 2;
        var security = new ApiSecurity(config, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertTrue(security.allow("one"));
        assertTrue(security.allow("one"));
        assertFalse(security.allow("one"));
        assertTrue(security.allow("two"));
    }

    @Test
    void invalidRuntimeSettingsFailEarly() {
        var config = new ServiceConfiguration();
        config.credentials = java.util.Map.of("a", "long-enough-credential");
        config.leaseSeconds = 10;
        config.requestTimeoutSeconds = 10;
        assertThrows(IllegalArgumentException.class, config::validateSettings);
    }

    @Test void kafkaAuthenticationEscapesCredentialsAndKeepsHostnameVerification() {
        var config=new KafkaSettings();config.securityProtocol="SASL_SSL";
        config.username="user\"name";config.password="pass\\word";config.caCertificate="test-ca";
        var properties=config.properties("test");
        var values=new java.util.HashMap<String,Object>();properties.forEach((k,v)->values.put(k.toString(),v));
        values.put("sasl.jaas.config",new org.apache.kafka.common.config.types.Password(properties.getProperty("sasl.jaas.config")));
        var context=org.apache.kafka.common.security.JaasContext.loadClientContext(values);
        var options=context.configurationEntries().getFirst().getOptions();
        assertEquals(config.username,options.get("username"));assertEquals(config.password,options.get("password"));
        assertEquals("https",properties.get("ssl.endpoint.identification.algorithm"));
        assertEquals("PEM",properties.get("ssl.truststore.type"));
    }
    @Test void webhookAddressPolicyBlocksPrivateMetadataAndReservedAddresses() throws Exception {
        for(var address:java.util.List.of("127.0.0.1","10.0.0.1","169.254.169.254","100.100.100.200","192.0.2.1","198.51.100.1","203.0.113.1","::1","fc00::1","2001:db8::1"))
            assertFalse(WebhookPolicy.publicAddress(java.net.InetAddress.getByName(address)),address);
        assertTrue(WebhookPolicy.publicAddress(java.net.InetAddress.getByName("8.8.8.8")));
        assertTrue(WebhookPolicy.publicAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test void productionCannotUseDemoOrUnencryptedConfiguration() {
        var config=new ServiceConfiguration();config.production=true;config.signingSecret="local-signing-secret-change-me";
        config.credentials=java.util.Map.of("service","local-demo-token-change-me");
        assertThrows(IllegalArgumentException.class,config::validateSettings);
    }
    private ServiceConfiguration dropletConfig() {
        var config = new ServiceConfiguration();
        config.production = true;
        config.adminToken = "independent-admin-token-at-least-32-characters";
        config.singleHostDeployment = true;
        config.signingSecret = "random-signing-secret-at-least-32-characters";
        config.credentials = java.util.Map.of("service", "random-api-token-at-least-32-characters");
        config.database.setUrl("jdbc:postgresql://db:5432/notifications");
        config.database.setProperties(java.util.Map.of("sslmode", "disable"));
        config.kafka.bootstrapServers = "kafka:9092";
        config.redis.password="test-redis-password-at-least-32-characters";
        config.redis.tls=true;config.redis.requiredReplicas=1;
        config.redis.sentinelPassword="test-sentinel-password-at-least-32-characters";
        config.redis.sentinels=java.util.List.of("10.0.0.1:26379","10.0.0.2:26379","10.0.0.3:26379");
        return config;
    }

    @Test void singleHostModeKeepsSecretsAndWebhookRestrictions() {
        var config = dropletConfig();
        assertDoesNotThrow(config::validateSettings);
        assertDoesNotThrow(config::prepareDatabaseSecurity);
        config.allowLocalWebhooks = true;
        assertThrows(IllegalArgumentException.class, config::validateSettings);
        config.allowLocalWebhooks = false;
        config.credentials = java.util.Map.of("service", "local-demo-token-change-me");
        assertThrows(IllegalArgumentException.class, config::validateSettings);
    }

    @Test void plaintextProductionIsRestrictedToSingleHostServiceNames() {
        var config = dropletConfig();
        config.kafka.bootstrapServers = "external.example.com:9092";
        assertThrows(IllegalArgumentException.class, config::validateSettings);
        config = dropletConfig();
        config.database.setUrl("jdbc:postgresql://external.example.com:5432/notifications");
        assertThrows(IllegalArgumentException.class, config::validateSettings);
        config = dropletConfig();
        config.singleHostDeployment = false;
        assertThrows(IllegalArgumentException.class, config::validateSettings);
    }

    @Test void connectionLookupRejectsDnsRebindingAndMixedAddresses() throws Exception {
        var config=new ServiceConfiguration();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new WebhookPolicy(config,origin->true,host->new java.net.InetAddress[]{
                java.net.InetAddress.getByName(calls.getAndIncrement()==0?"8.8.8.8":"127.0.0.1")});
        assertNotNull(policy.validate("https://receiver.example/hook"));
        assertThrows(java.net.UnknownHostException.class,()->policy.resolver().resolve("receiver.example"));
        var mixed=new WebhookPolicy(config,origin->true,host->new java.net.InetAddress[]{
                java.net.InetAddress.getByName("8.8.8.8"),java.net.InetAddress.getByName("169.254.169.254")});
        assertThrows(IllegalArgumentException.class,()->mixed.validate("https://receiver.example/hook"));
    }

    @Test void administratorOriginApprovalKeepsPublicHttpsBoundary() {
        var policy=new WebhookPolicy(new ServiceConfiguration(),origin->true);
        assertEquals("https://8.8.8.8:443",policy.validateOrigin("https://8.8.8.8/"));
        for(var value:List.of("http://8.8.8.8","https://127.0.0.1","https://169.254.169.254", "https://8.8.8.8/path", "https://8.8.8.8?query=x", "https://user@8.8.8.8", "https://8.8.8.8:0"))
            assertThrows(IllegalArgumentException.class,()->policy.validateOrigin(value),value);
    }

    @Test void administratorCannotReuseTenantCredential() {
        var config=dropletConfig();
        config.adminToken=config.credentials.get("service");
        assertThrows(IllegalArgumentException.class,config::validateSettings);
    }

    @Test void kafkaMutualTlsRequiresAllCredentialsAndValidatesHostnames() {
        var config=new KafkaSettings();config.securityProtocol="SSL";
        assertThrows(IllegalArgumentException.class,()->config.properties("test"));
        config.caCertificate="test-ca";config.clientCertificate="test-client";
        assertThrows(IllegalArgumentException.class,()->config.properties("test"));
        config.clientPrivateKey="test-private-key";
        var properties=config.properties("test");
        assertEquals("SSL",properties.get("security.protocol"));
        assertEquals("https",properties.get("ssl.endpoint.identification.algorithm"));
        assertEquals("PEM",properties.get("ssl.keystore.type"));
        assertEquals(config.clientCertificate,properties.get("ssl.keystore.certificate.chain"));
        assertEquals(config.clientPrivateKey,properties.get("ssl.keystore.key"));
        assertFalse(properties.containsKey("sasl.jaas.config"));
    }
    @Test void multiHostProductionRequiresEncryptedKafka() {
        var config=dropletConfig();config.singleHostDeployment=false;
        config.kafka.securityProtocol="SSL";
        assertDoesNotThrow(config::validateSettings);
        config.kafka.securityProtocol="PLAINTEXT";
        assertThrows(IllegalArgumentException.class,config::validateSettings);
    }

    @Test void eventLoggingContextRestoresExecutorThreadAfterException() {
        org.slf4j.MDC.put("event_id","previous");
        var id=java.util.UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->{try(var context=new EventLog(id,"tenant")) {
            assertEquals(id.toString(),org.slf4j.MDC.get("event_id"));throw new IllegalStateException("test");
        }});
        assertEquals("previous",org.slf4j.MDC.get("event_id"));org.slf4j.MDC.clear();
    }

    @Test void replicaPoolCannotSilentlyTargetPrimary() {
        var config=new ServiceConfiguration();config.requireReplicaReads=true;
        assertThrows(IllegalArgumentException.class,config::validateSettings);
        config.readDatabase=new io.dropwizard.db.DataSourceFactory();
        config.readDatabase.setValidationQuery("SELECT 1");
        config.readDatabase.setProperties(java.util.Map.of("targetServerType","primary"));
        assertThrows(IllegalArgumentException.class,config::validateSettings);
    }

    @Test void loopExecutorsRejectExtraWorkInsteadOfAccumulatingTasks() throws Exception {
        var blocker=new java.util.concurrent.CountDownLatch(1);
        var pool=BoundedExecutors.loops("bounded-test",1);
        try {
            pool.submit(()->{try{blocker.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertThrows(java.util.concurrent.RejectedExecutionException.class,()->pool.submit(()->{}));
        } finally {blocker.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test void sentinelConfigurationRequiresDistinctValidEndpoints() {
        var settings = new RedisSettings();
        settings.sentinels = List.of("node1:26379", "node1:26379", "node1:26379");
        assertThrows(IllegalArgumentException.class, () -> settings.validate(false, false));
        for (var port : List.of("0", "65536")) {
            settings.sentinels = List.of("node1:" + port);
            assertThrows(IllegalArgumentException.class, () -> settings.validate(false, false));
        }
    }
}
