package com.example.notifications;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.lifecycle.Managed;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Durable DB outbox -> Kafka -> idempotent delivery activation. */
public final class KafkaPipeline implements Managed {
    private static final Logger LOG=LoggerFactory.getLogger(KafkaPipeline.class);
    private final Store store;
    private final ObjectMapper json;
    private final KafkaSettings config;
    private final MetricRegistry metrics;
    private final ExecutorService executor;
    private volatile boolean running;
    private volatile long lastConsumerPoll=System.nanoTime();
    private KafkaProducer<String,String> producer;
    private KafkaConsumer<String,String> consumer;

    public KafkaPipeline(Store store,ObjectMapper json,KafkaSettings config,MetricRegistry metrics) {
        this.store=store;this.json=json;this.config=config;this.metrics=metrics;
        executor=Executors.newFixedThreadPool(config.publisherThreads+2);
    }
    @Override public void start() {
        if(config.publisherEnabled) {
            var properties=config.properties("notification-outbox-"+UUID.randomUUID());
            properties.put("key.serializer",StringSerializer.class.getName());
            properties.put("value.serializer",StringSerializer.class.getName());
            properties.put("acks","all");
            properties.put("enable.idempotence","true");
            properties.put("max.in.flight.requests.per.connection","1");
            properties.put("max.block.ms","5000");
            properties.put("delivery.timeout.ms","10000");
            properties.put("linger.ms","5");
            properties.put("max.request.size","1048576");
            producer=new KafkaProducer<>(properties);
        }
        if(config.consumerEnabled) {
            var properties=config.properties("notification-dispatch-"+UUID.randomUUID());
            properties.put("key.deserializer",StringDeserializer.class.getName());
            properties.put("value.deserializer",StringDeserializer.class.getName());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG,config.groupId);
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
            properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,"false");
            properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,"50");
            properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,"600000");
            consumer=new KafkaConsumer<>(properties);
        }
        running=true;
        if(producer!=null) for(var i=0;i<config.publisherThreads;i++) executor.submit(this::publishLoop);
        if(producer!=null) executor.submit(this::deadLetterLoop);
        if(consumer!=null) executor.submit(this::consumeLoop);
    }
    private void publishLoop() {
        while(running && !Thread.currentThread().isInterrupted()) {
            Store.Outbox outbox=null;
            try {
                outbox=store.claimOutbox();
                if(outbox==null) {pause(100);continue;}
                try (var context = new EventLog(outbox.eventId(), outbox.tenant())) {
                var envelope=json.createObjectNode().put("schemaVersion",1).put("tenantId",outbox.tenant());
                envelope.set("event",json.valueToTree(outbox.event()));
                var record = new ProducerRecord<String,String>(config.topic,outbox.eventId().toString(),json.writeValueAsString(envelope));
                record.headers().add("X-Event-ID",outbox.eventId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                producer.send(record).get(10,TimeUnit.SECONDS);
                store.finishPublish(outbox,true);
                metrics.counter("kafka.published").inc();
                LOG.info("event_id={} kafka_published topic={}",outbox.eventId(),config.topic);
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch(Exception e) {
                metrics.counter("kafka.publish_errors").inc();
                LOG.warn("event_id={} kafka_publish_failed error_type={}",outbox==null?null:outbox.eventId(),e.getClass().getSimpleName());
                if(outbox!=null) {
                    try {store.finishPublish(outbox,false);}
                    catch(RuntimeException dbError) {LOG.warn("outbox_release_failed; lease recovery will retry");}
                }
                pause(1000);
            }
        }
    }
    private void deadLetterLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            Store.DeadLetterPublish claim = null;
            try {
                claim = store.claimDeadLetter();
                if (claim == null) { pause(250); continue; }
                try (var context = new EventLog(claim.event().id(),claim.tenant())) {
                    var envelope=json.createObjectNode().put("schemaVersion",1).put("tenantId",claim.tenant())
                            .put("deliveryId",claim.deliveryId().toString()).put("attemptCount",claim.attempts()).put("reason",claim.reason());
                    envelope.set("event",json.valueToTree(claim.event()));
                    var record=new ProducerRecord<String,String>(config.deadLetterTopic,claim.deliveryId().toString(),json.writeValueAsString(envelope));
                    record.headers().add("X-Event-ID",claim.event().id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    producer.send(record).get(10,TimeUnit.SECONDS);
                    store.finishDeadLetter(claim);
                    metrics.counter("kafka.dead_letters_published").inc();
                    LOG.info("event_id={} delivery_id={} dead_letter_published topic={}",claim.event().id(),claim.deliveryId(),config.deadLetterTopic);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                metrics.counter("kafka.dead_letter_publish_errors").inc();
                LOG.warn("event_id={} dead_letter_publish_failed error_type={}",claim==null?null:claim.event().id(),e.getClass().getSimpleName());
                // Durable row stays unpublished; its bounded lease permits another publisher to retry.
                pause(1000);
            }
        }
    }

    private void consumeLoop() {
        try {
            consumer.subscribe(List.of(config.topic),new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                public void onPartitionsRevoked(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {}
                public void onPartitionsAssigned(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                    var committed=consumer.committed(new java.util.HashSet<>(partitions));
                    for(var partition:partitions) {
                        var offset=committed.get(partition);
                        if(offset==null)consumer.seekToBeginning(List.of(partition));else consumer.seek(partition,offset.offset());
                    }
                }
            });
            while(running) {
                try {
                    var records=consumer.poll(Duration.ofMillis(500));
                    lastConsumerPoll=System.nanoTime();
                    // Handle every partition separately. On failure rewind only that partition;
                    // never skip unprocessed records from another partition in the same poll.
                    for(var partition:records.partitions()) {
                        for(var record:records.records(partition)) {
                            try {
                                String tenant=null;
                                UUID eventId=null;
                                try {
                                    var envelope=json.readTree(record.value());
                                    if(envelope==null || envelope.path("schemaVersion").asInt()!=1)
                                        throw new IllegalArgumentException("Unsupported envelope");
                                    tenant=envelope.path("tenantId").asText();
                                    if(tenant.isBlank() || tenant.length()>100) throw new IllegalArgumentException("Invalid tenant");
                                    eventId=UUID.fromString(envelope.path("event").path("id").asText());
                                } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
                                    store.quarantine(record.topic(),record.partition(),record.offset(),record.value());
                                    metrics.counter("kafka.quarantined").inc();
                                }
                                if(eventId!=null) {
                                    try (var context = new EventLog(eventId,tenant)) {
                                        store.activateDispatch(tenant,eventId);
                                        metrics.counter("kafka.dispatched").inc();
                                        LOG.info("event_id={} kafka_dispatched partition={} offset={}",eventId,record.partition(),record.offset());
                                    }
                                }
                                // DB commit precedes the offset commit. Redelivery is harmless.
                                consumer.commitSync(Map.of(partition,new OffsetAndMetadata(record.offset()+1)));
                            } catch(RuntimeException e) {
                                if(!running) break;
                                metrics.counter("kafka.dispatch_errors").inc();
                                LOG.warn("event_id={} kafka_dispatch_failed partition={} offset={} error_type={}",record.key(),record.partition(),record.offset(),e.getClass().getSimpleName());
                                consumer.seek(partition,record.offset());
                                pause(500);
                                break;
                            }
                        }
                    }
                } catch(WakeupException e) {if(running)throw e;}
                catch(RuntimeException e) {
                    metrics.counter("kafka.consumer_errors").inc();
                    LOG.warn("kafka_consumer_failed error_type={}",e.getClass().getSimpleName());
                    pause(1000);
                }
            }
        } finally {consumer.close(Duration.ofSeconds(5));}
    }
    public boolean healthy() {
        return running && (!config.consumerEnabled || System.nanoTime()-lastConsumerPoll<TimeUnit.SECONDS.toNanos(60));
    }
    private static void pause(long millis) {
        try {Thread.sleep(millis);} catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    @Override public void stop() throws InterruptedException {
        running=false;
        if(consumer!=null)consumer.wakeup();
        executor.shutdown();
        if(!executor.awaitTermination(15,TimeUnit.SECONDS))executor.shutdownNow();
        if(producer!=null)producer.close(Duration.ofSeconds(5));
    }
}
