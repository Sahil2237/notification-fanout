package com.example.notifications;

import com.codahale.metrics.MetricRegistry;

import io.dropwizard.lifecycle.Managed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class DeliveryWorker implements Managed {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorker.class);
    private final Store store;
    private final WebhookSender sender;
    private final ServiceConfiguration config;
    private final MetricRegistry metrics;
    private final RetryPolicy retries;
    private final ExecutorService workers;
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running;
    private volatile long lastPoll = System.nanoTime();

    public DeliveryWorker(
            Store store,
            WebhookSender sender,
            ServiceConfiguration config,
            MetricRegistry metrics) {
        this.store = store;
        this.sender = sender;
        this.config = config;
        this.metrics = metrics;
        retries =
                new RetryPolicy(
                        config.maxAttempts,
                        config.retryBaseMillis,
                        config.retryCapMillis,
                        () -> ThreadLocalRandom.current().nextDouble());
        workers = Executors.newFixedThreadPool(config.workerThreads);
    }

    @Override
    public void start() {
        running = true;
        recovery.scheduleWithFixedDelay(
                () -> {
                    try {
                        metrics.counter("delivery.recovered")
                                .inc(store.recover(config.maxAttempts));
                    } catch (RuntimeException e) {
                        LOG.warn("recovery_failed error_type={}", e.getClass().getSimpleName());
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);
        for (var i = 0; i < config.workerThreads; i++) workers.submit(this::loop);
    }

    private void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                var claim = store.claim(config.leaseSeconds, config.maxAttempts);
                lastPoll = System.nanoTime();
                if (claim == null) {
                    pause(200);
                    continue;
                }
                try (var context = new EventLog(claim.event().id(), claim.tenant())) {
                var result = sender.send(claim);
                var saved = store.finish(claim, result, retries);
                metrics.counter("delivery.attempts").inc();
                if (!saved) metrics.counter("delivery.stale_results").inc();
                else if (result.httpStatus() != null
                        && result.httpStatus() >= 200
                        && result.httpStatus() < 300) metrics.counter("delivery.succeeded").inc();
                else metrics.counter("delivery.unsuccessful_attempts").inc();
                LOG.info(
                        "delivery_id={} event_id={} attempt={} http_status={} error={}"
                            + " result_saved={}",
                        claim.id(),
                        claim.event().id(),
                        claim.number(),
                        result.httpStatus(),
                        result.error(),
                        saved);
                }
            } catch (RuntimeException e) {
                metrics.counter("delivery.worker_errors").inc();
                LOG.warn("worker_iteration_failed error_type={}", e.getClass().getSimpleName());
                pause(1000);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean healthy() {
        return running && System.nanoTime() - lastPoll < TimeUnit.SECONDS.toNanos(30);
    }

    @Override
    public void stop() throws InterruptedException {
        running = false;
        recovery.shutdown();
        workers.shutdown();
        if (!workers.awaitTermination(config.requestTimeoutSeconds + 5L, TimeUnit.SECONDS))
            workers.shutdownNow();
        recovery.awaitTermination(5, TimeUnit.SECONDS);
        sender.close();
    }
}
