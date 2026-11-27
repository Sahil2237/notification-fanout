package com.example.notifications;

import static com.example.notifications.Models.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.DropwizardTestSupport;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ServiceIT {
    private static final String TOKEN = "local-demo-token-change-me";
    private static final ObjectMapper JSON = Jackson.newObjectMapper();
    private static final Jdbi DB =
            Jdbi.create(
                    System.getenv()
                            .getOrDefault(
                                    "TEST_DB_URL",
                                    "jdbc:postgresql://notification-db:5432/notifications_test"),
                    System.getenv().getOrDefault("DB_USER", "notifications"),
                    System.getenv().getOrDefault("DB_PASSWORD", "local-only-change-me"));
    private static final Store STORE = new Store(DB, JSON);
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final DropwizardTestSupport<ServiceConfiguration> APP =
            new DropwizardTestSupport<>(NotificationApplication.class, "config/test.yml");
    private static HttpServer receiver;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile int failUntil;
    private static volatile int forcedStatus;
    private static volatile String signature;
    private static volatile String receivedBody;
    private static volatile String receivedTime;

    @BeforeAll
    static void start() throws Exception {
        new NotificationApplication().run("db", "migrate", "config/test.yml");
        APP.before();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 19090), 0);
        receiver.createContext(
                "/hook",
                exchange -> {
                    var count = CALLS.incrementAndGet();
                    receivedBody =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    signature = exchange.getRequestHeaders().getFirst("X-Webhook-Signature");
                    receivedTime = exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp");
                    var status = forcedStatus != 0 ? forcedStatus : count <= failUntil ? 500 : 204;
                    if (status == 302)
                        exchange.getResponseHeaders()
                                .add("Location", "http://127.0.0.1:19090/target");
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        receiver.createContext("/slow",exchange->{
            exchange.getRequestBody().readAllBytes();
            try {Thread.sleep(2000);exchange.sendResponseHeaders(204,-1);}
            catch(InterruptedException error){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });
        receiver.createContext(
                "/target",
                exchange -> {
                    CALLS.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        receiver.start();
    }

    @AfterAll
    static void stop() {
        if (receiver != null) receiver.stop(0);
        APP.after();
        HTTP.close();
    }

    @BeforeEach
    void clean() {
        DB.useHandle(
                h ->
                        h.execute(
                                "TRUNCATE delivery_attempts,deliveries,events,subscriptions"
                                    + " CASCADE"));
        CALLS.set(0);
        failUntil = 0;
        forcedStatus = 0;
    }

    private EventInput input(String payload) throws Exception {
        return new EventInput("order.created", "checkout", JSON.readTree(payload));
    }

    private Subscription subscribe(String tenant, Rules rules) {
        return STORE.createSubscription(
                tenant, new SubscriptionInput("http://127.0.0.1:19090/hook", rules));
    }

    private RetryPolicy retry(int max) {
        return new RetryPolicy(max, 1, 1, () -> 0);
    }

    private WebhookSender sender() {
        return new WebhookSender(
                JSON, new WebhookPolicy(APP.getConfiguration()), APP.getConfiguration());
    }

    private HttpResponse<String> api(String method, String path, String token, String body)
            throws Exception {
        return api(method, path, token, body, null);
    }

    private HttpResponse<String> api(String method, String path, String token, String body, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + APP.getLocalPort() + path))
                        .timeout(Duration.ofSeconds(10));
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void subscriptionHttpReplayReturnsOriginalCreationAndLocation() throws Exception {
        var body = "{\"webhookUrl\":\"http://127.0.0.1:19090/hook\",\"filter\":{\"type\":\"order.created\"}}";
        var first = api("POST", "/v1/subscriptions", TOKEN, body, "subscription-create");
        var replay = api("POST", "/v1/subscriptions", TOKEN, body, "subscription-create");
        assertEquals(201, first.statusCode());
        assertEquals(201, replay.statusCode());
        assertEquals(JSON.readTree(first.body()), JSON.readTree(replay.body()));
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(1, STORE.subscriptions("demo", 100, 0).size());
        var id = JSON.readTree(first.body()).path("id").asText();
        assertEquals(204, api("DELETE", "/v1/subscriptions/" + id, TOKEN, null).statusCode());
        var deletedReplay = api("POST", "/v1/subscriptions", TOKEN, body, "subscription-create");
        assertEquals(201, deletedReplay.statusCode());
        assertEquals(JSON.readTree(first.body()), JSON.readTree(deletedReplay.body()));
        assertTrue(STORE.subscriptions("demo", 100, 0).isEmpty(), "Replay must not reactivate a deleted subscription");
    }

    @Test
    void subscriptionKeyConflictAndInvalidKeyAreRejected() throws Exception {
        var body = "{\"webhookUrl\":\"http://127.0.0.1:19090/hook\"}";
        assertEquals(201, api("POST", "/v1/subscriptions", TOKEN, body, "shared-key").statusCode());
        assertEquals(409, api("POST", "/v1/subscriptions", TOKEN, body.replace("/hook", "/slow"), "shared-key").statusCode());
        assertEquals(400, api("POST", "/v1/subscriptions", TOKEN, body, "x".repeat(201)).statusCode());
        assertEquals(400, api("POST", "/v1/subscriptions", TOKEN, body, " ").statusCode());
        assertEquals(401, api("POST", "/v1/subscriptions", null, body, "shared-key").statusCode());
        assertEquals(1, STORE.subscriptions("demo", 100, 0).size());
    }

    @Test
    void subscriptionIdempotencyIsTenantScopedAndOptional() {
        var request = new SubscriptionInput("http://127.0.0.1:19090/hook", Rules.all());
        var first = STORE.createSubscription("demo", request, "same");
        var second = STORE.createSubscription("other", request, "same");
        assertNotEquals(first.id(), second.id());
        assertFalse(STORE.ingest("demo", new EventInput("order.created", "checkout", JSON.createObjectNode()), "same").duplicate(), "Event and subscription keys have separate operation scopes");
        assertNotEquals(first.id(), STORE.createSubscription("demo", request, "another").id());
        assertNotEquals(STORE.createSubscription("demo", request).id(), STORE.createSubscription("demo", request).id());
    }

    @Test
    void subscriptionReplayCanonicalizesObjectFieldsAndDoesNotAdvanceGeneration() throws Exception {
        var first = new SubscriptionInput("http://127.0.0.1:19090/hook", new Rules("order.created", null,
                List.of(new Condition("/details", "eq", JSON.readTree("{\"a\":1,\"b\":2}")))));
        var reordered = new SubscriptionInput(first.webhookUrl(), new Rules("order.created", null,
                List.of(new Condition("/details", "eq", JSON.readTree("{\"b\":2,\"a\":1}")))));
        var created = STORE.createSubscription("demo", first, "canonical");
        var before = DB.withHandle(h -> h.createQuery("SELECT version FROM subscription_versions WHERE tenant_id='demo'").mapTo(Long.class).one());
        assertEquals(created, new Store(DB, JSON).createSubscription("demo", reordered, "canonical"));
        assertEquals(before, DB.withHandle(h -> h.createQuery("SELECT version FROM subscription_versions WHERE tenant_id='demo'").mapTo(Long.class).one()));
        var changed = new SubscriptionInput(first.webhookUrl(), new Rules("another.type", null, first.filter().payloadConditions()));
        var error = assertThrows(WebApplicationException.class, () -> STORE.createSubscription("demo", changed, "canonical"));
        assertEquals(409, error.getResponse().getStatus());
    }

    @Test
    void concurrentSubscriptionRetriesAcrossStoresCreateOneRecipient() throws Exception {
        var request = new SubscriptionInput("http://127.0.0.1:19090/hook", Rules.all());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (var i = 0; i < 12; i++) tasks.add(pool.submit(() -> new Store(DB, JSON).createSubscription("demo", request, "concurrent").id()));
            var ids = new java.util.HashSet<UUID>();
            for (var task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
        }
        var event = STORE.ingest("demo", input("{}"), "one-event").event();
        assertEquals(1, STORE.deliveries("demo", event.id(), true, 100, 0).size());
    }

    @Test
    void subscriptionReplaySucceedsAtCapWithoutCreatingOrChangingRules() {
        var request = new SubscriptionInput("http://127.0.0.1:19090/hook", Rules.all());
        var original = STORE.createSubscription("demo", request, "at-cap");
        DB.useHandle(h -> h.execute("INSERT INTO subscriptions(id,tenant_id,webhook_url,filter) SELECT gen_random_uuid(),'demo','http://127.0.0.1:19090/hook','{}'::jsonb FROM generate_series(1,999)"));
        assertEquals(original, STORE.createSubscription("demo", request, "at-cap"));
        var error = assertThrows(WebApplicationException.class, () -> STORE.createSubscription("demo", request, "new-at-cap"));
        assertEquals(409, error.getResponse().getStatus());
    }

    @Test
    void failedSubscriptionTransactionDoesNotReserveKeyOrAdvanceVersion() {
        var tenant = "rollback-" + UUID.randomUUID();
        var bad = new SubscriptionInput(null, Rules.all());
        assertThrows(RuntimeException.class, () -> STORE.createSubscription(tenant, bad, "retry-after-rollback"));
        var versions = DB.withHandle(h -> h.createQuery("SELECT count(*) FROM subscription_versions WHERE tenant_id=:tenant").bind("tenant", tenant).mapTo(Integer.class).one());
        assertEquals(0, versions.intValue());
        var valid = new SubscriptionInput("http://127.0.0.1:19090/hook", Rules.all());
        assertNotNull(STORE.createSubscription(tenant, valid, "retry-after-rollback"));
    }

    @Test
    void fullHttpJourneyAndAudit() throws Exception {
        var created =
                api(
                        "POST",
                        "/v1/subscriptions",
                        TOKEN,
                        "{\"webhookUrl\":\"http://127.0.0.1:19090/hook\",\"filter\":{\"type\":\"order.created\"}}");
        assertEquals(201, created.statusCode(), created.body());
        var subscription = JSON.readTree(created.body()).get("id").asText();
        var accepted =
                api(
                        "POST",
                        "/v1/events",
                        TOKEN,
                        "{\"type\":\"order.created\",\"source\":\"checkout\",\"payload\":{\"n\":1}}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        var event = JSON.readTree(accepted.body()).path("event").path("id").asText();
        var claim = STORE.claim(30, 5);
        assertNotNull(claim);
        try (var sender = sender()) {
            assertTrue(STORE.finish(claim, sender.send(claim), retry(5)));
        }
        var audit = api("GET", "/v1/events/" + event + "/deliveries", TOKEN, null);
        assertEquals(200, audit.statusCode(), audit.body());
        var delivery = JSON.readTree(audit.body()).path("items").get(0);
        assertEquals("delivered", delivery.path("status").asText());
        assertEquals(204, delivery.path("attempts").get(0).path("httpStatus").asInt());
        assertTrue(delivery.path("attempts").get(0).path("finishedAt").asText().endsWith("Z"));
        assertNotNull(audit.headers().firstValue("X-Request-ID").orElse(null));
        assertEquals(
                1,
                JSON.readTree(
                                api(
                                                "GET",
                                                "/v1/subscriptions/" + subscription + "/deliveries",
                                                TOKEN,
                                                null)
                                        .body())
                        .path("items")
                        .size());
        var key =
                WebhookSender.hmac(
                        APP.getConfiguration().signingSecret.getBytes(StandardCharsets.UTF_8),
                        "tenant:demo");
        assertEquals(
                "v1="
                        + java.util.HexFormat.of()
                                .formatHex(
                                        WebhookSender.hmac(key, receivedTime + "." + receivedBody)),
                signature);
    }

    @Test
    void fanoutFiltersTenantsAndDeletionBoundary() throws Exception {
        var first = subscribe("demo", Rules.all());
        subscribe("demo", new Rules("other", null, List.of()));
        subscribe("other", Rules.all());
        var second = subscribe("demo", Rules.all());
        var event = STORE.ingest("demo", input("null"), null).event();
        assertEquals(2, STORE.deliveries("demo", event.id(), true, 100, 0).size());
        STORE.deleteSubscription("demo", first.id());
        STORE.deleteSubscription("demo", first.id());
        var next = STORE.ingest("demo", input("[1,2]"), null).event();
        assertEquals(1, STORE.deliveries("demo", next.id(), true, 100, 0).size());
        assertEquals(1, STORE.deliveries("demo", first.id(), false, 100, 0).size());
        assertEquals(2, STORE.subscriptions("demo", 100, 0).size());
        assertThrows(NotFoundException.class, () -> STORE.event("other", event.id()));
        assertThrows(NotFoundException.class, () -> STORE.subscription("other", second.id()));
    }

    @Test
    void acceptsNoMatchesAndAllJsonKinds() throws Exception {
        for (var payload : List.of("null", "1", "true", "\"text\"", "[]", "{}")) {
            var event = STORE.ingest("demo", input(payload), null).event();
            assertEquals(JSON.readTree(payload), event.payload());
            assertTrue(STORE.deliveries("demo", event.id(), true, 10, 0).isEmpty());
        }
    }

    @Test
    void concurrentIdempotencyCreatesOneEventAndOneDelivery() throws Exception {
        subscribe("demo", Rules.all());
        var eventInput = input("{\"a\":1,\"b\":2}");
        var ids = ConcurrentHashMap.<UUID>newKeySet();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (var i = 0; i < 16; i++)
                tasks.add(
                        executor.submit(
                                () ->
                                        ids.add(
                                                STORE.ingest("demo", eventInput, "same-key")
                                                        .event()
                                                        .id())));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, ids.size());
        assertEquals(1, STORE.pendingCount());
        assertTrue(STORE.ingest("demo", input("{\"b\":2,\"a\":1}"), "same-key").duplicate());
        assertEquals(
                409,
                assertThrows(
                                WebApplicationException.class,
                                () -> STORE.ingest("demo", input("{}"), "same-key"))
                        .getResponse()
                        .getStatus());
        assertFalse(STORE.ingest("other", eventInput, "same-key").duplicate());
    }

    @Test
    void concurrentWorkersClaimDistinctJobs() throws Exception {
        for (var i = 0; i < 8; i++) subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        var ids = ConcurrentHashMap.<UUID>newKeySet();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (var i = 0; i < 8; i++)
                tasks.add(
                        executor.submit(
                                () -> {
                                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                    Claim claim = null;
                                    while (claim == null && System.nanoTime() < deadline) {
                                        claim = STORE.claim(30, 5);
                                        if (claim == null) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                                    }
                                    assertNotNull(claim);
                                    assertTrue(ids.add(claim.id()));
                                }));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(8, ids.size());
        assertNull(STORE.claim(30, 5));
    }

    @Test
    void realHttpFailuresRetryThenSucceed() throws Exception {
        subscribe("demo", Rules.all());
        var event = STORE.ingest("demo", input("{}"), null).event();
        failUntil = 2;
        try (var sender = sender()) {
            for (var i = 0; i < 3; i++) {
                var claim = awaitClaim();
                assertNotNull(claim);
                assertTrue(STORE.finish(claim, sender.send(claim), retry(5)));
            }
        }
        var delivery = STORE.deliveries("demo", event.id(), true, 10, 0).getFirst();
        assertEquals("delivered", delivery.status());
        assertEquals(
                List.of(500, 500, 204),
                delivery.attempts().stream().map(Attempt::httpStatus).toList());
        assertEquals(3, CALLS.get());
    }

    @Test
    void permanentResponseAndRetryExhaustionAreAudited() throws Exception {
        subscribe("demo", Rules.all());
        var event = STORE.ingest("demo", input("{}"), null).event();
        var claim = STORE.claim(30, 2);
        assertTrue(STORE.finish(claim, new Result(400, "http_400"), retry(2)));
        assertEquals(
                "failed", STORE.deliveries("demo", event.id(), true, 10, 0).getFirst().status());
        var next = STORE.ingest("demo", input("{}"), "separate-event").event();
        for (var i = 0; i < 2; i++)
            assertTrue(STORE.finish(awaitClaim(), new Result(null, "timeout"), retry(2)));
        var failed = STORE.deliveries("demo", next.id(), true, 10, 0).getFirst();
        assertEquals("failed", failed.status());
        assertEquals(2, failed.attemptCount());
        assertNull(failed.attempts().getFirst().httpStatus());
    }

    @Test
    void expiredLeaseRecoversAcrossStoreRestartAndFencesOldWorker() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        var old = STORE.claim(30, 5);
        DB.useHandle(
                h ->
                        h.createUpdate(
                                        "UPDATE deliveries SET"
                                            + " lease_expires_at=clock_timestamp()-interval '1"
                                            + " second' WHERE id=:id")
                                .bind("id", old.id())
                                .execute());
        var restarted = new Store(DB, JSON);
        assertEquals(1, restarted.recover(5));
        var replacement = restarted.claim(30, 5);
        assertEquals(old.id(), replacement.id());
        assertFalse(STORE.finish(old, new Result(204, null), retry(5)));
        assertTrue(restarted.finish(replacement, new Result(204, null), retry(5)));
        var history = restarted.delivery("demo", old.id());
        assertEquals("unknown", history.attempts().getFirst().outcome());
        assertNull(history.attempts().getFirst().httpStatus());
        assertEquals("delivered", history.status());
    }

    @Test
    void finalInterruptedAttemptBecomesFailed() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        var claim = STORE.claim(30, 1);
        DB.useHandle(
                h ->
                        h.execute(
                                "UPDATE deliveries SET lease_expires_at=clock_timestamp()-interval"
                                    + " '1 second'"));
        STORE.recover(1);
        assertEquals("failed", STORE.delivery("demo", claim.id()).status());
        assertNull(STORE.claim(30, 1));
    }

    @Test
    void eventAndFanoutRollbackTogether() throws Exception {
        subscribe("demo", Rules.all());
        DB.useHandle(
                h -> {
                    h.execute(
                            "CREATE FUNCTION reject_delivery_test() RETURNS trigger AS 'BEGIN RAISE"
                                + " EXCEPTION ''test failure''; END;' LANGUAGE plpgsql");
                    h.execute(
                            "CREATE TRIGGER reject_delivery_test BEFORE INSERT ON deliveries FOR"
                                + " EACH ROW EXECUTE FUNCTION reject_delivery_test()");
                });
        try {
            assertThrows(
                    RuntimeException.class, () -> STORE.ingest("demo", input("{}"), "rollback"));
            assertEquals(
                    0,
                    DB.withHandle(
                                    h ->
                                            h.createQuery("SELECT count(*) FROM events")
                                                    .mapTo(Integer.class)
                                                    .one())
                            .intValue());
        } finally {
            DB.useHandle(
                    h -> {
                        h.execute("DROP TRIGGER reject_delivery_test ON deliveries");
                        h.execute("DROP FUNCTION reject_delivery_test()");
                    });
        }
    }

    @Test
    void validationAuthenticationAndOwnershipOverHttp() throws Exception {
        assertEquals(200, api("GET", "/health/live", null, null).statusCode());
        assertEquals(401, api("GET", "/v1/subscriptions", null, null).statusCode());
        assertEquals(400, api("GET", "/v1/subscriptions?limit=101", TOKEN, null).statusCode());
        assertEquals(
                400,
                api("POST", "/v1/subscriptions", TOKEN, "{\"webhookUrl\":\"http://example.com\"}")
                        .statusCode());
        assertTrue(
                api("POST", "/v1/events", TOKEN, "{\"source\":\"x\",\"payload\":{}}").statusCode()
                        >= 400);
        assertEquals(413, api("POST", "/v1/events", TOKEN, "x".repeat(262145)).statusCode());
        var subscription = subscribe("demo", Rules.all());
        var event = STORE.ingest("demo", input("{}"), null).event();
        var other = "local-other-token-change-me";
        assertEquals(404, api("GET", "/v1/events/" + event.id(), other, null).statusCode());
        assertEquals(
                404,
                api("DELETE", "/v1/subscriptions/" + subscription.id(), other, null).statusCode());
        var delivery = STORE.deliveries("demo", event.id(), true, 10, 0).getFirst();
        assertEquals(404, api("GET", "/v1/deliveries/" + delivery.id(), other, null).statusCode());
    }

    @Test
    void redirectIsNotFollowedAndPolicyFailureHasNoInventedStatus() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        forcedStatus = 302;
        try (var sender = sender()) {
            var claim = STORE.claim(30, 5);
            var result = sender.send(claim);
            assertEquals(302, result.httpStatus());
            assertEquals(1, CALLS.get());
            STORE.finish(claim, result, retry(5));
            assertEquals("failed", STORE.delivery("demo", claim.id()).status());
            var invalid =
                    new Claim(
                            claim.id(),
                            claim.subscriptionId(),
                            claim.tenant(),
                            "http://unapproved.example",
                            claim.token(),
                            claim.number(),
                            claim.event());
            assertNull(sender.send(invalid).httpStatus());
        }
    }

    @Test
    void managedWorkerProcessesPersistedQueue() throws Exception {
        subscribe("demo", Rules.all());
        var event = STORE.ingest("demo", input("{}"), null).event();
        var config = APP.getConfiguration();
        var worker =
                new DeliveryWorker(
                        STORE, sender(), config, new com.codahale.metrics.MetricRegistry(),new TestLimiter());
        worker.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (STORE.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(25);
            assertEquals(0, STORE.pendingCount());
            assertTrue(worker.healthy());
            assertEquals(
                    "delivered",
                    STORE.deliveries("demo", event.id(), true, 10, 0).getFirst().status());
        } finally {
            worker.stop();
        }
    }

    @Test
    void httpAcceptsNullPayloadRejectsMissingPayloadAndBoundsPages() throws Exception {
        assertEquals(
                202,
                api(
                                "POST",
                                "/v1/events",
                                TOKEN,
                                "{\"type\":\"x\",\"source\":\"y\",\"payload\":null}")
                        .statusCode());
        assertTrue(
                api("POST", "/v1/events", TOKEN, "{\"type\":\"x\",\"source\":\"y\"}").statusCode()
                        >= 400);
        subscribe("demo", Rules.all());
        subscribe("demo", Rules.all());
        var first = JSON.readTree(api("GET", "/v1/subscriptions?limit=1", TOKEN, null).body());
        var second =
                JSON.readTree(api("GET", "/v1/subscriptions?limit=1&offset=1", TOKEN, null).body());
        assertTrue(first.path("hasMore").asBoolean());
        assertFalse(second.path("hasMore").asBoolean());
        assertNotEquals(
                first.path("items").get(0).path("id"), second.path("items").get(0).path("id"));
    }

    @Test
    void retryScheduleIsNotClaimedEarly() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        var claim = STORE.claim(30, 5);
        STORE.finish(claim, new Result(429, "http_429"), new RetryPolicy(5, 10000, 10000, () -> 0));
        assertNull(STORE.claim(30, 5));
        var delivery = STORE.delivery("demo", claim.id());
        assertEquals("pending", delivery.status());
        assertEquals("retry_scheduled", delivery.attempts().getFirst().outcome());
    }

    @Test
    void lostSuccessResultCausesDuplicateWithStableDeliveryIdentity() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        try (var sender = sender()) {
            var first = STORE.claim(30, 5);
            assertEquals(204, sender.send(first).httpStatus());
            // Simulate death after receiver success, before saving the result.
            DB.useHandle(
                    h ->
                            h.execute(
                                    "UPDATE deliveries SET"
                                        + " lease_expires_at=clock_timestamp()-interval '1"
                                        + " second'"));
            STORE.recover(5);
            var again = STORE.claim(30, 5);
            assertEquals(first.id(), again.id());
            assertTrue(STORE.finish(again, sender.send(again), retry(5)));
            assertEquals(2, CALLS.get());
            assertEquals(
                    "unknown", STORE.delivery("demo", again.id()).attempts().getFirst().outcome());
        }
    }

    @Test
    void connectionFailureIsRecordedWithoutHttpStatus() throws Exception {
        subscribe("demo", Rules.all());
        STORE.ingest("demo", input("{}"), null);
        var original = STORE.claim(30, 5);
        var config = new ServiceConfiguration();
        config.allowLocalWebhooks = true;
        config.allowedWebhookOrigins = List.of("http://127.0.0.1:9099");
        config.signingSecret = "test-only-signing-secret-change-me";
        var claim =
                new Claim(
                        original.id(),
                        original.subscriptionId(),
                        original.tenant(),
                        "http://127.0.0.1:9099/hook",
                        original.token(),
                        original.number(),
                        original.event());
        try (var sender = new WebhookSender(JSON, new WebhookPolicy(config), config)) {
            var result = sender.send(claim);
            assertNull(result.httpStatus());
            assertEquals("transport_error", result.error());
            STORE.finish(claim, result, retry(5));
            assertNull(STORE.delivery("demo", claim.id()).attempts().getFirst().httpStatus());
        }
    }

    private Claim awaitClaim() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var claim = STORE.claim(30, 5);
            if (claim != null) return claim;
            Thread.sleep(5);
        }
        fail("No delivery became due");
        return null;
    }
    @Test void administratorCanApproveAndRevokeOriginsWithoutRestart() throws Exception {
        var admin=APP.getConfiguration().adminToken;
        var origin="http://127.0.0.1:19091";
        var path="/v1/admin/webhook-origins";
        assertEquals(401,api("GET",path,null,null).statusCode());
        assertEquals(403,api("GET",path,TOKEN,null).statusCode());
        assertEquals(401,api("GET","/v1/subscriptions",admin,null).statusCode());
        assertEquals(400,api("POST",path,admin,"{\"origin\":\"http://127.0.0.1:19091/path\"}").statusCode());
        assertEquals(200,api("POST",path,admin,JSON.writeValueAsString(java.util.Map.of("origin",origin))).statusCode());
        assertTrue(api("GET",path,admin,null).body().contains(origin));
        var freshStore=new Store(DB,JSON);
        assertTrue(freshStore.isWebhookOriginApproved(origin));
        var input=JSON.writeValueAsString(new SubscriptionInput(origin+"/hook",Rules.all()));
        var created=api("POST","/v1/subscriptions",TOKEN,input);
        assertEquals(201,created.statusCode(),created.body());
        var id=JSON.readTree(created.body()).path("id").asText();
        assertTrue(api("GET","/v1/subscriptions",TOKEN,null).body().contains(id));
        assertEquals(204,api("DELETE","/v1/subscriptions/"+id,TOKEN,null).statusCode());
        var encoded=java.net.URLEncoder.encode(origin,StandardCharsets.UTF_8);
        assertEquals(204,api("DELETE",path+"?origin="+encoded,admin,null).statusCode());
        assertFalse(freshStore.isWebhookOriginApproved(origin));
        STORE.initializeWebhookOrigins(List.of(origin));
        assertFalse(freshStore.isWebhookOriginApproved(origin),"Startup must not resurrect a revoked origin");
        assertEquals(400,api("POST","/v1/subscriptions",TOKEN,input).statusCode());
        var changes=DB.withHandle(h->h.createQuery("SELECT count(*) FROM webhook_origin_audit WHERE origin=:origin")
                .bind("origin",origin).mapTo(Integer.class).one());
        assertTrue(changes>=2);
    }

    @Test void realSlowReceiverIsCancelledAndAuditedForRetry() throws Exception {
        var config=new ServiceConfiguration();
        config.allowLocalWebhooks=true;config.allowedWebhookOrigins=APP.getConfiguration().allowedWebhookOrigins;
        config.signingSecret=APP.getConfiguration().signingSecret;config.requestTimeoutSeconds=1;config.workerThreads=1;
        STORE.createSubscription("demo",new SubscriptionInput("http://127.0.0.1:19090/slow",Rules.all()));
        STORE.ingest("demo",input("{}"),null);
        var claim=STORE.claim(30,5);
        var started=System.nanoTime();
        try(var sender=new WebhookSender(JSON,new WebhookPolicy(config),config)) {
            var result=sender.send(claim);
            assertNull(result.httpStatus());assertNotNull(result.error());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<3500);
            assertTrue(STORE.finish(claim,result,retry(5)));
        }
        var delivery=STORE.delivery("demo",claim.id());
        assertEquals("pending",delivery.status());
        assertEquals("retry_scheduled",delivery.attempts().getFirst().outcome());
        assertNull(delivery.attempts().getFirst().httpStatus());
    }

    @Test void capacityRejectionRollsBackNewEventButAllowsIdempotentRetry() throws Exception {
        var bounded=new Store(DB,JSON,true,1);
        var first=bounded.ingest("demo",input("{}"),"capacity-first");
        var failure=assertThrows(jakarta.ws.rs.WebApplicationException.class,
                ()->bounded.ingest("demo",input("{}"),"capacity-second"));
        assertEquals(503,failure.getResponse().getStatus());
        assertTrue(bounded.ingest("demo",input("{}"),"capacity-first").duplicate());
        assertEquals(1,DB.withHandle(h->h.createQuery("SELECT count(*) FROM events").mapTo(Integer.class).one()).intValue());
        assertEquals(first.event().id(),bounded.ingest("demo",input("{}"),"capacity-first").event().id());
    }

    @Test void databaseWriteFailureReturns503AndDoesNotAcknowledgeOrPersistEvent() throws Exception {
        DB.useHandle(h->{
            h.execute("CREATE OR REPLACE FUNCTION notification_test_disk_full() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated storage failure' USING ERRCODE='53100'; END $$");
            h.execute("CREATE TRIGGER notification_test_disk_full BEFORE INSERT ON events FOR EACH ROW EXECUTE FUNCTION notification_test_disk_full()");
        });
        try {
            var response=api("POST","/v1/events",TOKEN,"{\"type\":\"test\",\"source\":\"storage-test\",\"payload\":{}}");
            assertEquals(503,response.statusCode(),response.body());
            assertEquals(0,DB.withHandle(h->h.createQuery("SELECT count(*) FROM events").mapTo(Integer.class).one()).intValue());
        } finally {
            DB.useHandle(h->{h.execute("DROP TRIGGER notification_test_disk_full ON events");h.execute("DROP FUNCTION notification_test_disk_full()");});
        }
        assertEquals(202,api("POST","/v1/events",TOKEN,"{\"type\":\"test\",\"source\":\"storage-test\",\"payload\":{}}").statusCode());
    }

    @Test void generatedIdempotencyIsCanonicalScopedAndConcurrent() throws Exception {
        subscribe("demo",Rules.all());
        var identity=new Identity("demo","user-a","compartment-a");
        var original=input("{\"b\":2,\"a\":1}");
        var ids=ConcurrentHashMap.<UUID>newKeySet();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(var n=0;n<16;n++) tasks.add(pool.submit(()->ids.add(STORE.ingest(identity,original,null).event().id())));
            for(var task:tasks) task.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,ids.size());
        assertTrue(STORE.ingest(identity,input("{\"a\":1,\"b\":2}"),null).duplicate());
        assertFalse(STORE.ingest(new Identity("demo","user-b","compartment-a"),original,null).duplicate());
        assertFalse(STORE.ingest(new Identity("demo","user-a","compartment-b"),original,null).duplicate());
        var producer=UUID.randomUUID();
        var identified=new EventInput("type","source",JSON.readTree("{}"),producer);
        var accepted=STORE.ingest(identity,identified,null);
        assertEquals(accepted.event().id(),STORE.ingest(identity,identified,null).event().id());
        assertEquals(409,assertThrows(WebApplicationException.class,()->STORE.ingest(identity,
                new EventInput("type","source",JSON.readTree("{\"changed\":true}"),producer),null)).getResponse().getStatus());
    }

    @Test void subscriberBulkheadIsGlobalAndSchedulerIsFair() throws Exception {
        var a=subscribe("demo",new Rules("a",null,List.of()));
        var b=subscribe("demo",new Rules("b",null,List.of()));
        for(var n=0;n<10;n++) STORE.ingest("demo",new EventInput("a","test",JSON.readTree("{}")),"a-"+n);
        STORE.ingest("demo",new EventInput("b","test",JSON.readTree("{}")),"b");
        DB.useHandle(h->{h.createUpdate("UPDATE subscriptions SET last_claimed_at=to_timestamp(0) WHERE id=:id").bind("id",a.id()).execute();h.createUpdate("UPDATE subscriptions SET last_claimed_at=to_timestamp(1) WHERE id=:id").bind("id",b.id()).execute();});
        var first=STORE.claim(30,6);assertEquals(a.id(),first.subscriptionId());
        var second=new Store(DB,JSON).claim(30,6);assertEquals(b.id(),second.subscriptionId());
        // A slow first subscriber cannot take another slot while its lease remains active.
        assertNull(STORE.claim(30,6));
        assertTrue(STORE.finish(second,new Result(204,null),retry(6)));
        assertNull(STORE.claim(30,6));
        assertTrue(STORE.finish(first,new Result(204,null),retry(6)));
        assertEquals(a.id(),STORE.claim(30,6).subscriptionId());
    }

    @Test void concurrentInstancesCannotExceedSubscriberReservationLimit() throws Exception {
        subscribe("demo",Rules.all());
        for(var n=0;n<20;n++) STORE.ingest("demo",input("{}"),"parallel-"+n);
        var other=new Store(DB,JSON);var claims=new java.util.ArrayList<java.util.concurrent.Future<Claim>>();
        try(var pool=Executors.newFixedThreadPool(16)) {
            for(var n=0;n<16;n++) {var instance=n%2==0?STORE:other;claims.add(pool.submit(()->instance.claim(30,6)));}
            var claimed=0;
            for(var future:claims) if(future.get(15,TimeUnit.SECONDS)!=null)claimed++;
            assertEquals(1,claimed);
        }
        assertEquals(1,DB.withHandle(h->h.createQuery("SELECT count(*) FROM deliveries WHERE lease_expires_at>clock_timestamp()").mapTo(Integer.class).one()).intValue());
    }

    @Test void sixAttemptsCreateExactlyOneTransactionalDeliveryDeadLetter() throws Exception {
        subscribe("demo",Rules.all());var event=STORE.ingest("demo",input("{}"),null).event();
        Claim finalClaim=null;
        for(var attempt=1;attempt<=6;attempt++) {
            DB.useHandle(h->h.execute("UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second'"));
            var claim=STORE.claim(30,6);assertNotNull(claim);assertEquals(attempt,claim.number());
            assertTrue(STORE.finish(claim,new Result(503,"http_503"),retry(6)));finalClaim=claim;
            assertEquals(attempt<6?"pending":"failed",STORE.delivery("demo",claim.id()).status());
            assertEquals(attempt<6?0:1,STORE.deadLetters("demo",100,0).size());
        }
        assertFalse(STORE.finish(finalClaim,new Result(503,"http_503"),retry(6)));
        var dead=STORE.deadLetters("demo",100,0).getFirst();assertEquals(event.id(),dead.eventId());assertEquals(6,dead.attemptCount());
        assertEquals("retries_exhausted",dead.reason());assertNull(STORE.claim(30,6));assertTrue(STORE.deadLetters("other",100,0).isEmpty());
        assertEquals(200,api("GET","/v1/dead-letters",TOKEN,null).statusCode());
    }

    @Test void failedDlqInsertRollsBackDeliveryAndAttemptCompletion() throws Exception {
        subscribe("demo",Rules.all());STORE.ingest("demo",input("{}"),null);var claim=STORE.claim(30,6);
        DB.useHandle(h->{h.execute("CREATE FUNCTION notification_test_dlq_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test DLQ write failure'; END $$");h.execute("CREATE TRIGGER notification_test_dlq_failure BEFORE INSERT ON delivery_dead_letters FOR EACH ROW EXECUTE FUNCTION notification_test_dlq_failure()");});
        try {assertThrows(RuntimeException.class,()->STORE.finish(claim,new Result(400,"http_400"),retry(6)));}
        finally {DB.useHandle(h->{h.execute("DROP TRIGGER notification_test_dlq_failure ON delivery_dead_letters");h.execute("DROP FUNCTION notification_test_dlq_failure()");});}
        var before=STORE.delivery("demo",claim.id());assertEquals("pending",before.status());assertNull(before.attempts().getFirst().finishedAt());
        assertTrue(STORE.finish(claim,new Result(400,"http_400"),retry(6)));assertEquals(1,STORE.deadLetters("demo",100,0).size());
    }

    @Test void strictReplicaReadBoundaryRejectsPrimaryButWritesStillWork() throws Exception {
        var replicaPool=Jdbi.create(System.getenv().getOrDefault("TEST_DB_URL","jdbc:postgresql://notification-db:5432/notifications_test"),
                System.getenv().getOrDefault("DB_USER","notifications"),System.getenv().getOrDefault("DB_PASSWORD","local-only-change-me"));
        var routed=new Store(DB,replicaPool,JSON,false,100000,1);
        var event=routed.ingest("demo",input("{}"),null).event();
        assertEquals(503,assertThrows(WebApplicationException.class,()->routed.event("demo",event.id())).getResponse().getStatus());
        assertEquals(event.id(),STORE.event("demo",event.id()).id());
        assertFalse(routed.healthy());assertTrue(STORE.healthy());
    }

    @Test void restGeneratedIdempotencyReturnsOriginalIdAndCorrelationHeader() throws Exception {
        var body="{\"type\":\"correlation\",\"source\":\"test\",\"payload\":{}}";
        var first=api("POST","/v1/events",TOKEN,body);var second=api("POST","/v1/events",TOKEN,body);
        assertEquals(202,first.statusCode());assertEquals(202,second.statusCode());
        var id=JSON.readTree(first.body()).path("event").path("id").asText();
        assertEquals(id,JSON.readTree(second.body()).path("event").path("id").asText());
        assertEquals(id,first.headers().firstValue("X-Event-ID").orElseThrow());assertTrue(JSON.readTree(second.body()).path("duplicate").asBoolean());
    }

    @Test void retryBudgetDeferralDoesNotConsumeAnHttpAttemptAndIsFenced() throws Exception {
        subscribe("demo",Rules.all());var event=STORE.ingest("demo",input("{}"),null).event();
        var first=STORE.claim(30,6);STORE.finish(first,new Result(503,"http_503"),retry(6));
        DB.useHandle(h->h.execute("UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second'"));
        var reservation=STORE.reserve(30,6);assertEquals(2,reservation.number());
        assertTrue(STORE.deferForRetryBudget(reservation));assertFalse(STORE.startAttempt(reservation));
        var delivery=STORE.delivery("demo",reservation.id());assertEquals(1,delivery.attemptCount());assertEquals(1,delivery.attempts().size());
        assertEquals("pending",delivery.status());assertTrue(STORE.deadLetters("demo",10,0).isEmpty());assertNull(STORE.claim(30,6));
    }

}
