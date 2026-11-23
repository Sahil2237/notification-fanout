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
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + APP.getLocalPort() + path))
                        .timeout(Duration.ofSeconds(10));
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
                                    var claim = STORE.claim(30, 5);
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
        var next = STORE.ingest("demo", input("{}"), null).event();
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
                        STORE, sender(), config, new com.codahale.metrics.MetricRegistry());
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
}
