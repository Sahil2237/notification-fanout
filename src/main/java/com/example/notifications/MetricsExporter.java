package com.example.notifications;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.lifecycle.Managed;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import io.opentelemetry.sdk.common.export.RetryPolicy;

/** Fixed allowlist keeps metric cardinality bounded and prevents exporting request data. */
public final class MetricsExporter implements Managed {
    static final Map<String,String> COUNTERS = Map.ofEntries(
        Map.entry("http.success", "notification.http.success"),
        Map.entry("http.client.errors", "notification.http.client.errors"),
        Map.entry("http.server.errors", "notification.http.server.errors"),
        Map.entry("http.rate.limited", "notification.http.rate.limited"),
        Map.entry("events.accepted", "notification.events.accepted"),
        Map.entry("events.duplicates", "notification.events.duplicates"),
        Map.entry("delivery.attempts", "notification.delivery.attempts"),
        Map.entry("delivery.succeeded", "notification.delivery.succeeded"),
        Map.entry("delivery.unsuccessful_attempts", "notification.delivery.unsuccessful.attempts"),
        Map.entry("delivery.retry_budget_deferred", "notification.delivery.retry.budget.deferred"),
        Map.entry("delivery.stale_results", "notification.delivery.stale.results"),
        Map.entry("delivery.worker_errors", "notification.delivery.worker.errors"),
        Map.entry("delivery.recovered", "notification.delivery.recovered"),
        Map.entry("kafka.published", "notification.kafka.published"),
        Map.entry("kafka.publish_errors", "notification.kafka.publish.errors"),
        Map.entry("kafka.dispatched", "notification.kafka.dispatched"),
        Map.entry("kafka.consumer_errors", "notification.kafka.consumer.errors"),
        Map.entry("kafka.quarantined", "notification.kafka.quarantined"),
        Map.entry("kafka.dead_letters_published", "notification.kafka.dead.letters.published"),
        Map.entry("kafka.dead_letter_publish_errors", "notification.kafka.dead.letter.publish.errors"));
    static final Map<String,String> GAUGES = Map.of(
        "metrics.scrape.success", "notification.metrics.scrape.success",
        "events.acceptance_latency_p95_seconds", "notification.events.acceptance.latency.p95",
        "delivery.pending", "notification.delivery.pending",
        "delivery.oldest_pending_seconds", "notification.delivery.oldest.pending.seconds",
        "kafka.outbox_pending", "notification.kafka.outbox.pending",
        "kafka.oldest_undispatched_seconds", "notification.kafka.oldest.undispatched.seconds",
        "redis.replicas", "notification.redis.replicas",
        "http.admission.waiting", "notification.http.admission.waiting",
        "http.admission.rejected", "notification.http.admission.rejected");

    private final SdkMeterProvider provider;
    private final java.util.concurrent.ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService transportExecutor;
    private record Transport(MetricExporter exporter, ExecutorService executor) { }

    public MetricsExporter(MetricRegistry registry, MetricsSettings settings) {
        this(registry, settings, transport(settings));
    }

    private static Transport transport(MetricsSettings settings) {
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1, true), task -> new Thread(task, "metrics-http"),
            new ThreadPoolExecutor.AbortPolicy());
        try {
            var exporter = OtlpHttpMetricExporter.builder().setEndpoint(settings.endpoint)
                .addHeader("Authorization", "Bearer " + settings.token())
                .setExecutorService(executor).setConnectTimeout(Duration.ofSeconds(2))
                .setTimeout(Duration.ofSeconds(3)).setMaxRequestBodySize(65536)
                .setRetryPolicy(RetryPolicy.builder().setMaxAttempts(1).build())
                .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred()).build();
            return new Transport(exporter, executor);
        } catch (RuntimeException failure) { executor.shutdownNow(); throw failure; }
    }

    private MetricsExporter(MetricRegistry registry, MetricsSettings settings, Transport transport) {
        this(registry, settings, transport.exporter(), transport.executor());
    }

    // Package private injection keeps tests independent of cloud credentials and external networks.
    MetricsExporter(MetricRegistry registry, MetricsSettings settings, MetricExporter exporter) {
        this(registry, settings, exporter, null);
    }

    private MetricsExporter(MetricRegistry registry, MetricsSettings settings,
                            MetricExporter exporter, ExecutorService transportExecutor) {
        this.transportExecutor = transportExecutor;
        scheduler = BoundedExecutors.poller("metrics-export");
        var reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofSeconds(settings.intervalSeconds)).setExecutor(scheduler).build();
        var attributes = Attributes.builder()
            .put("service.name", settings.serviceName)
            .put("resource.urn", "do:droplet:" + settings.dropletId)
            .put("resource.type", "droplet")
            .put("tenant.urn", "do:team:" + settings.teamId)
            .put("resource.region.slug", settings.region).build();
        provider = SdkMeterProvider.builder().setResource(Resource.create(attributes))
            .registerView(InstrumentSelector.builder().setName("otel.*").build(),
                View.builder().setAggregation(Aggregation.drop()).build())
            .registerMetricReader(reader).build();
        var meter = provider.get("notification-fanout");
        var labels = Attributes.builder().put("service.name", settings.serviceName).build();
        COUNTERS.forEach((source, target) -> {
            var counter = registry.counter(source);
            meter.counterBuilder(target).buildWithCallback(value -> value.record(counter.getCount(), labels));
        });
        GAUGES.forEach((source, target) -> meter.gaugeBuilder(target).buildWithCallback(value -> {
            var metric = registry.getGauges().get(source);
            if (metric == null) return;
            try {
                var sample = metric.getValue();
                if (sample instanceof Number number && Double.isFinite(number.doubleValue()))
                    value.record(number.doubleValue(), labels);
            } catch (RuntimeException unavailable) {
                // An unavailable dependency must not block collection of other metrics.
            }
        }));

    }

    @Override public void start() { }
    void flush() { provider.forceFlush().join(5, TimeUnit.SECONDS); }
    @Override public void stop() {
        provider.shutdown().join(5, TimeUnit.SECONDS);
        scheduler.shutdownNow();
        if (transportExecutor != null) transportExecutor.shutdownNow();
    }
}
