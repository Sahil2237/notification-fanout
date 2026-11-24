package com.example.notifications;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only bridge for an already-running service. Never consumes events or changes its database. */
public final class MetricsBridge {
    static final URI LOCAL_METRICS = URI.create("http://127.0.0.1:8081/metrics");
    private final MetricRegistry registry = new MetricRegistry();
    private final Map<String,Long> previous = new HashMap<>();
    private volatile Map<String,Double> gauges = Map.of();
    private final ObjectMapper json = new ObjectMapper();

    MetricsBridge() {
        MetricsExporter.GAUGES.keySet().forEach(name -> registry.register(name, (Gauge<Double>) () -> gauges.get(name)));
    }

    synchronized void accept(JsonNode root) {
        var current = new HashMap<String,Double>();
        MetricsExporter.COUNTERS.forEach((source,target) -> {
            var value = root.path("counters").path(source).path("count");
            if (!value.isIntegralNumber() || value.longValue() < 0) return;
            var observed = value.longValue();
            var before = previous.put(source, observed);
            // Baseline the first scrape. Following a service restart count only new occurrences.
            if (before != null) registry.counter(source).inc(observed >= before ? observed - before : observed);
        });
        MetricsExporter.GAUGES.forEach((source,target) -> {
            var value = root.path("gauges").path(source).path("value");
            if (value.isNumber() && Double.isFinite(value.doubleValue())) current.put(source, value.doubleValue());
        });
        var timer = root.path("timers").path("events.acceptance_latency");
        var percentile = timer.path("p95");
        if (percentile.isNumber() && "seconds".equals(timer.path("duration_units").asText()))
            current.put("events.acceptance_latency_p95_seconds", percentile.doubleValue());
        current.put("metrics.scrape.success", 1.0);
        gauges = Map.copyOf(current);
    }

    void scrape() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) LOCAL_METRICS.toURL().openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new IOException("Metrics source unavailable");
            try (var input = connection.getInputStream()) {
                var bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Metrics source exceeds size bound");
                accept(json.readTree(bytes));
            }
        } catch (IOException | RuntimeException failure) {
            // Suppress old gauges so a source outage cannot masquerade as a fresh queue/health value.
            gauges = Map.of("metrics.scrape.success", 0.0);
        } finally { if (connection != null) connection.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        var settings = new MetricsSettings(); settings.enabled = true;
        settings.dropletId = System.getenv().getOrDefault("DO_DROPLET_ID", "");
        settings.teamId = System.getenv().getOrDefault("DO_TEAM_ID", "");
        settings.region = System.getenv().getOrDefault("DO_REGION", "blr1");
        settings.endpoint = "https://otlp-insights-" + settings.region + ".digitalocean.com/v1/metrics";
        settings.validate();
        var bridge = new MetricsBridge(); bridge.scrape();
        var exporter = new MetricsExporter(bridge.registry, settings);
        var scraper = BoundedExecutors.poller("metrics-scrape");
        scraper.scheduleWithFixedDelay(bridge::scrape, settings.intervalSeconds, settings.intervalSeconds, TimeUnit.SECONDS);
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scraper.shutdownNow(); exporter.stop(); stopped.countDown();
        }, "metrics-shutdown"));
        System.out.println("Metrics-only bridge started; source=loopback interval_seconds=" + settings.intervalSeconds);
        stopped.await();
    }

    MetricRegistry registry() { return registry; }
}
