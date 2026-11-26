package com.example.notifications;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.health.HealthCheck;

import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.migrations.MigrationsBundle;

import java.time.Clock;

public final class NotificationApplication extends Application<ServiceConfiguration> {
    public static void main(String[] args) throws Exception {
        new NotificationApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<ServiceConfiguration> bootstrap) {
        bootstrap.getObjectMapper().registerSubtypes(new com.fasterxml.jackson.databind.jsontype.NamedType(BoundedServerFactory.class,"bounded"));
        bootstrap
                .getObjectMapper()
                .disable(
                        com.fasterxml.jackson.databind.SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS);
        bootstrap
                .getObjectMapper()
                .getFactory()
                .setStreamReadConstraints(
                        com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                .maxNestingDepth(32)
                                .maxNumberLength(256)
                                .build());
        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(
                        bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)));
        bootstrap.addBundle(
                new MigrationsBundle<ServiceConfiguration>() {
                    @Override
                    public DataSourceFactory getDataSourceFactory(ServiceConfiguration config) {
                        config.validateSettings();
                        config.prepareDatabaseSecurity();
                        return config.database;
                    }
                });
    }

    @Override
    public void run(ServiceConfiguration config, Environment environment) {
        config.validateSettings();
        config.prepareDatabaseSecurity();
        var limiter=new RedisRateLimiter(config.redis);
        environment.lifecycle().manage(limiter);
        environment.metrics().register("redis.replicas",(Gauge<Integer>)limiter::replicaCount);
        var db = new JdbiFactory().build(environment, config.database, "postgresql");
        var reads = config.readDatabase == null ? db : new JdbiFactory().build(environment, config.readDatabase, "postgresql-replicas");
        var store = new Store(db, reads, environment.getObjectMapper(), config.kafka.enabled, config.maxQueuedEvents, config.subscriberConcurrency);
        if(config.cache.enabled) {
            var cacheRedis=environment.getObjectMapper().convertValue(config.redis,RedisSettings.class);
            cacheRedis.poolSize=2;cacheRedis.timeoutMillis=200;
            var cache=new RedisApplicationCache(new RedisRateLimiter(cacheRedis),config.cache,environment.metrics());
            environment.lifecycle().manage(cache);
            store.configureCache(cache,config.cache);
        }
        KafkaPipeline pipeline=null;
        if(config.kafka.enabled) {
            pipeline=new KafkaPipeline(store,environment.getObjectMapper(),config.kafka,environment.metrics());
            environment.lifecycle().manage(pipeline);
        }
        environment.metrics().register("kafka.outbox_pending",(Gauge<Long>)store::outboxPending);
        environment.metrics().register("kafka.oldest_undispatched_seconds",(Gauge<Double>)store::outboxOldestSeconds);
        store.initializeWebhookOrigins(config.allowedWebhookOrigins);
        var destinations = new WebhookPolicy(config,store::isWebhookOriginApproved);
        environment.jersey().register(new AdminResource(store,destinations));
        DeliveryWorker worker = null;
        if (config.workerEnabled) {
            var sender = new WebhookSender(environment.getObjectMapper(), destinations, config);
            worker = new DeliveryWorker(store, sender, config, environment.metrics(),limiter);
            environment.lifecycle().manage(worker);
        }
        environment.jersey().register(new ApiSecurity(config, Clock.systemUTC(), limiter, environment.metrics()));
        environment.jersey().register(new ApiErrors());
        environment
                .jersey()
                .register(new NotificationResource(store, destinations, environment.metrics(), config));
        environment.jersey().register(new HealthResource(store, worker, pipeline,limiter));
        environment
                .healthChecks()
                .register(
                        "persistence",
                        new HealthCheck() {
                            @Override
                            protected Result check() {
                                return store.healthy()
                                        ? Result.healthy()
                                        : Result.unhealthy("Database unavailable");
                            }
                        });
        environment.metrics().register("delivery.pending", (Gauge<Long>) store::pendingCount);
        environment
                .metrics()
                .register(
                        "delivery.oldest_pending_seconds",
                        (Gauge<Double>) store::oldestPendingSeconds);
        environment.metrics().register("events.acceptance_latency_p95_seconds", (Gauge<Double>) () ->
            environment.metrics().timer("events.acceptance_latency").getSnapshot().get95thPercentile() / 1_000_000_000.0);
        if (config.telemetry.enabled) {
            environment.lifecycle().manage(new MetricsExporter(environment.metrics(), config.telemetry));
        }
    }
}
