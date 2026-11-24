package com.example.notifications;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsExporterTest {
    private MetricsSettings settings() {
        var settings = new MetricsSettings();
        settings.dropletId = "622664599";
        settings.teamId = "01234567-0123-4123-8123-0123456789ab";
        return settings;
    }

    @Test void emitsDeltaCountersBoundedLabelsAndRequiredResourceAttributes() throws Exception {
        var registry = new MetricRegistry();
        var capture = new Capture();
        registry.register("delivery.pending", (Gauge<Long>) () -> 7L);
        registry.register("redis.replicas", (Gauge<Integer>) () -> { throw new IllegalStateException(); });
        registry.counter("private.event.payload.secret").inc();
        var bridge = new MetricsExporter(registry, settings(), capture);
        try {
            registry.counter("events.accepted").inc(10);
            bridge.flush();
            registry.counter("events.accepted").inc(3);
            bridge.flush();
            var accepted = capture.last.stream().filter(m -> m.getName().equals("notification.events.accepted")).findFirst().orElseThrow();
            assertEquals(AggregationTemporality.DELTA, accepted.getLongSumData().getAggregationTemporality());
            assertEquals(3, accepted.getLongSumData().getPoints().iterator().next().getValue());
            assertEquals("do:droplet:622664599", accepted.getResource().getAttribute(AttributeKey.stringKey("resource.urn")));
            assertEquals("do:team:" + settings().teamId, accepted.getResource().getAttribute(AttributeKey.stringKey("tenant.urn")));
            assertEquals("blr1", accepted.getResource().getAttribute(AttributeKey.stringKey("resource.region.slug")));
            var labels = accepted.getLongSumData().getPoints().iterator().next().getAttributes();
            assertEquals(1, labels.size());
            assertEquals("notification-fanout", labels.get(AttributeKey.stringKey("service.name")));
            assertTrue(capture.last.stream().noneMatch(m -> m.getName().contains("private") || m.getName().contains("_")), capture.last.stream().map(MetricData::getName).toList().toString());
            assertEquals(7, capture.last.stream().filter(m -> m.getName().equals("notification.delivery.pending"))
                .findFirst().orElseThrow().getDoubleGaugeData().getPoints().iterator().next().getValue());
        } finally { bridge.stop(); }
    }

    @Test void exportsActualOtlpHttpProtobufWithAuthentication() throws Exception {
        var received = new ArrayBlockingQueue<String>(2);
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            received.offer(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("Authorization")
                + "|" + exchange.getRequestHeaders().getFirst("Content-Type") + "|" + new String(body, StandardCharsets.ISO_8859_1));
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        var transport = OtlpHttpMetricExporter.builder().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics")
            .addHeader("Authorization", "Bearer test-only-ingest-token").setTimeout(Duration.ofSeconds(1))
            .setAggregationTemporalitySelector(type -> AggregationTemporality.DELTA).build();
        var bridge = new MetricsExporter(new MetricRegistry(), settings(), transport);
        try {
            bridge.flush();
            var request = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertTrue(request.startsWith("POST|Bearer test-only-ingest-token|application/x-protobuf|"));
            assertTrue(request.contains("notification.events.accepted"));
            assertTrue(request.contains("service.name"));
            assertFalse(request.substring(request.indexOf("application/x-protobuf|")).contains("test-only-ingest-token"));
        } finally { bridge.stop(); server.stop(0); }
    }

    @Test void rejectsWrongRegionAndUntrustedEndpoint() {
        var settings = settings(); settings.enabled = true;
        settings.endpoint = "https://example.com/v1/metrics";
        assertThrows(IllegalArgumentException.class, settings::validate);
        settings.endpoint = "https://otlp-insights-nyc3.digitalocean.com/v1/metrics";
        assertThrows(IllegalArgumentException.class, settings::validate);
        settings.enabled = false; assertDoesNotThrow(settings::validate);
    }

    static class Capture implements MetricExporter {
        volatile List<MetricData> last = List.of();
        @Override public AggregationTemporality getAggregationTemporality(InstrumentType type) { return AggregationTemporality.DELTA; }
        @Override public CompletableResultCode export(Collection<MetricData> metrics) { last = List.copyOf(metrics); return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
