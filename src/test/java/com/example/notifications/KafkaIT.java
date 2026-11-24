package com.example.notifications;

import static com.example.notifications.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

class KafkaIT {
    private static final ObjectMapper JSON=Jackson.newObjectMapper();
    private static final Jdbi DB=Jdbi.create(System.getenv().getOrDefault("TEST_DB_URL","jdbc:postgresql://notification-db:5432/notifications_test"),
        System.getenv().getOrDefault("DB_USER","notifications"),System.getenv().getOrDefault("DB_PASSWORD","local-only-change-me"));
    private static final Store STORE=new Store(DB,JSON,true);
    private static final DropwizardTestSupport<ServiceConfiguration> APP=new DropwizardTestSupport<>(NotificationApplication.class,"config/test.yml",
        ConfigOverride.config("kafka.enabled","true"),ConfigOverride.config("kafka.publisherEnabled","false"),ConfigOverride.config("kafka.consumerEnabled","false"));
    private KafkaSettings settings;
    private AdminClient admin;

    @BeforeAll static void migrateAndStartApi() throws Exception {
        new NotificationApplication().run("db","migrate","config/test.yml");APP.before();
    }
    @AfterAll static void stopApi(){APP.after();}
    @BeforeEach void prepareTopic() throws Exception {
        DB.useHandle(h->h.execute("TRUNCATE fixed_window_limits,kafka_dead_letters,delivery_attempts,deliveries,events,subscriptions CASCADE"));
        settings=new KafkaSettings();
        settings.bootstrapServers=System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS","notification-kafka:9092");
        settings.topic="notification-test-"+UUID.randomUUID();settings.deadLetterTopic=settings.topic+"-dlq";settings.groupId="test-"+UUID.randomUUID();
        admin=AdminClient.create(settings.properties("test-admin"));
        admin.createTopics(List.of(new NewTopic(settings.topic,6,(short)1),new NewTopic(settings.deadLetterTopic,1,(short)1))).all().get(20,TimeUnit.SECONDS);
    }
    @AfterEach void removeTopic() throws Exception {
        if(admin!=null){try{admin.deleteTopics(List.of(settings.topic,settings.deadLetterTopic)).all().get(10,TimeUnit.SECONDS);}finally{admin.close(Duration.ofSeconds(3));}}
    }
    private Event accept() throws Exception {
        return STORE.ingest("demo",new EventInput("single.type","test",JSON.readTree("{\"hello\":\"world\"}")),UUID.randomUUID().toString()).event();
    }
    private void subscription(){STORE.createSubscription("demo",new SubscriptionInput("http://127.0.0.1:19090/hook",Rules.all()));}
    private KafkaPipeline pipeline(boolean publish,boolean consume){
        settings.publisherEnabled=publish;settings.consumerEnabled=consume;
        return new KafkaPipeline(STORE,JSON,settings,new MetricRegistry());
    }
    @Test void fullRestKafkaAndWebhookJourney() throws Exception {
        var received=new AtomicInteger();
        var receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",19090),32);
        receiver.createContext("/hook",exchange->{exchange.getRequestBody().readAllBytes();received.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});receiver.start();
        subscription();
        Event event;
        try(var http=HttpClient.newHttpClient()){
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+APP.getLocalPort()+"/v1/events"))
                .header("Authorization","Bearer local-demo-token-change-me").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"single.type\",\"source\":\"test\",\"payload\":null}")).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(202,response.statusCode(),response.body());
            var id=UUID.fromString(JSON.readTree(response.body()).path("event").path("id").asText());event=STORE.event("demo",id);
        }
        assertEquals(1,STORE.outboxPending());assertNull(STORE.claim(30,5));assertEquals(0,received.get());
        var pipeline=pipeline(true,true);
        var worker=new DeliveryWorker(STORE,new WebhookSender(JSON,new WebhookPolicy(APP.getConfiguration()),APP.getConfiguration()),APP.getConfiguration(),new MetricRegistry());
        pipeline.start();worker.start();
        try {
            await(()->"delivered".equals(STORE.deliveries("demo",event.id(),true,10,0).getFirst().status()),20);
            assertEquals(1,received.get());assertEquals(204,STORE.deliveries("demo",event.id(),true,10,0).getFirst().attempts().getFirst().httpStatus());
            await(()->STORE.outboxPending()==0,10);
        }finally{worker.stop();pipeline.stop();receiver.stop(0);}
    }
    @Test void publisherAndConsumerRestartKeepWork() throws Exception {
        subscription();var event=accept();var publisher=pipeline(true,false);publisher.start();
        try{await(()->STORE.outboxPending()==0,20);assertNull(STORE.claim(30,5));}finally{publisher.stop();}
        var consumer=pipeline(false,true);consumer.start();
        try{await(()->ready(event.id()),20);assertNotNull(STORE.claim(30,5));}finally{consumer.stop();}
    }
    @Test void duplicateKafkaMessagesDoNotDuplicateJobs() throws Exception {
        subscription();var event=accept();
        send(event,0);send(event,0);
        var consumer=pipeline(false,true);consumer.start();
        try{await(()->ready(event.id()),20);assertEquals(1,STORE.deliveries("demo",event.id(),true,100,0).size());
            // A concurrent duplicate dispatch may briefly hold the row lock. Workers poll again
            // after SKIP LOCKED; a null claim during that interval does not mean work was lost.
            var claimed=new AtomicReference<Claim>();
            await(()->{var candidate=STORE.claim(30,5);if(candidate==null)return false;claimed.set(candidate);return true;},10);
            assertNotNull(claimed.get());assertNull(STORE.claim(30,5));
        }finally{consumer.stop();}
    }
    @Test void malformedRecordIsDurablyQuarantinedBeforeContinuing() throws Exception {
        subscription();var event=accept();
        try(var producer=producer()){producer.send(new ProducerRecord<>(settings.topic,0,"bad","not-json")).get(10,TimeUnit.SECONDS);}
        send(event,0);
        var consumer=pipeline(false,true);consumer.start();
        try{await(()->ready(event.id()),20);
            assertEquals(1,DB.withHandle(h->h.createQuery("SELECT count(*) FROM kafka_dead_letters").mapTo(Integer.class).one()).intValue());
        }finally{consumer.stop();}
    }
    @Test void unavailableBrokerDoesNotLoseAcceptedEvent() throws Exception {
        subscription();var event=accept();var good=settings.bootstrapServers;settings.bootstrapServers="127.0.0.1:1";
        var unavailable=pipeline(true,false);unavailable.start();
        try{
            await(()->DB.withHandle(h->h.createQuery("SELECT publish_attempts FROM event_outbox WHERE event_id=:id").bind("id",event.id()).mapTo(Integer.class).one())>0,10);
            assertEquals(1,STORE.outboxPending());assertNull(STORE.claim(30,5));
        }finally{unavailable.stop();}
        settings.bootstrapServers=good;var recovered=pipeline(true,true);recovered.start();
        try{await(()->ready(event.id()),25);await(()->STORE.outboxPending()==0,10);}finally{recovered.stop();}
    }
    @Test void failedDispatchDoesNotSkipAnotherPartition() throws Exception {
        subscription();var first=accept();var second=accept();
        DB.useHandle(h->{h.execute("CREATE FUNCTION reject_dispatch_test() RETURNS trigger AS 'BEGIN IF NEW.event_id = ''"+first.id()+"''::uuid THEN RAISE EXCEPTION ''test failure''; END IF; RETURN NEW; END;' LANGUAGE plpgsql");
            h.execute("CREATE TRIGGER reject_dispatch_test BEFORE UPDATE ON deliveries FOR EACH ROW EXECUTE FUNCTION reject_dispatch_test()");});
        send(first,0);send(second,1);var consumer=pipeline(false,true);consumer.start();
        try{
            await(()->ready(second.id()),20);assertFalse(ready(first.id()));
            DB.useHandle(h->{h.execute("DROP TRIGGER reject_dispatch_test ON deliveries");h.execute("DROP FUNCTION reject_dispatch_test()");});
            await(()->ready(first.id()),20);
        }finally{
            consumer.stop();DB.useHandle(h->{h.execute("DROP TRIGGER IF EXISTS reject_dispatch_test ON deliveries");h.execute("DROP FUNCTION IF EXISTS reject_dispatch_test()");});
        }
    }
    @Test void eventJobsAndOutboxRollbackAtomically() throws Exception {
        subscription();
        DB.useHandle(h->{h.execute("CREATE FUNCTION reject_outbox_test() RETURNS trigger AS 'BEGIN RAISE EXCEPTION ''test failure''; END;' LANGUAGE plpgsql");
            h.execute("CREATE TRIGGER reject_outbox_test BEFORE INSERT ON event_outbox FOR EACH ROW EXECUTE FUNCTION reject_outbox_test()");});
        try{assertThrows(RuntimeException.class,this::accept);
            assertEquals(0,DB.withHandle(h->h.createQuery("SELECT count(*) FROM events").mapTo(Integer.class).one()).intValue());
            assertEquals(0,STORE.pendingCount());
        }finally{DB.useHandle(h->{h.execute("DROP TRIGGER reject_outbox_test ON event_outbox");h.execute("DROP FUNCTION reject_outbox_test()");});}
    }
    @Test void outboxLeaseRecoveryFencesOldPublisher() throws Exception {
        accept();var first=STORE.claimOutbox();assertNotNull(first);assertNull(STORE.claimOutbox());
        DB.useHandle(h->h.execute("UPDATE event_outbox SET lease_expires_at=clock_timestamp()-interval '1 second'"));
        var second=STORE.claimOutbox();assertNotEquals(first.token(),second.token());
        STORE.finishPublish(first,true);assertEquals(1,STORE.outboxPending());
        STORE.finishPublish(second,true);assertEquals(0,STORE.outboxPending());
    }
    @Test void noMatchEventsStillTraverseKafka() throws Exception {
        var event=accept();assertEquals(1,STORE.outboxPending());var pipeline=pipeline(true,true);pipeline.start();
        try{await(()->DB.withHandle(h->h.createQuery("SELECT dispatched_at IS NOT NULL FROM event_outbox WHERE event_id=:id").bind("id",event.id()).mapTo(Boolean.class).one()),20);
            assertTrue(STORE.deliveries("demo",event.id(),true,10,0).isEmpty());
        }finally{pipeline.stop();}
    }
    private boolean ready(UUID id){return DB.withHandle(h->h.createQuery("SELECT COALESCE(bool_and(dispatch_ready),false) FROM deliveries WHERE event_id=:id").bind("id",id).mapTo(Boolean.class).one());}
    private KafkaProducer<String,String> producer(){var p=settings.properties("test-producer");p.put("key.serializer",StringSerializer.class.getName());p.put("value.serializer",StringSerializer.class.getName());p.put("acks","all");p.put("max.in.flight.requests.per.connection","1");p.put("delivery.timeout.ms","10000");p.put("max.block.ms","5000");return new KafkaProducer<>(p);}
    private void send(Event event,int partition) throws Exception {try(var producer=producer()){
        var envelope=JSON.createObjectNode().put("schemaVersion",1).put("tenantId","demo");envelope.set("event",JSON.valueToTree(event));
        producer.send(new ProducerRecord<>(settings.topic,partition,event.id().toString(),JSON.writeValueAsString(envelope))).get(10,TimeUnit.SECONDS);
    }}
    private static void await(BooleanSupplier condition,int seconds) throws InterruptedException {
        var deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(50);}fail("Condition not met within "+seconds+" seconds");
    }
    @Test void tenThousandSingleTypeEventsDistributeAcrossSixPartitions() throws Exception {
        var counts=new int[6];
        try(var producer=producer()) {
            var sends=new java.util.ArrayList<java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata>>();
            for(var index=0;index<10000;index++) {
                var id=new UUID(0,index).toString();
                sends.add(producer.send(new ProducerRecord<>(settings.topic,id,"{\"type\":\"single.type\"}")));
            }
            for(var send:sends) counts[send.get(20,TimeUnit.SECONDS).partition()]++;
        }
        assertEquals(10000,java.util.Arrays.stream(counts).sum());
        for(var count:counts) assertTrue(count>0 && count<5000,java.util.Arrays.toString(counts));
    }

    @Test void failedDeliveryIsDurablyPublishedToSeparateKafkaDlqWithEventCorrelation() throws Exception {
        subscription();var event=accept();STORE.activateDispatch("demo",event.id());
        var claim=STORE.claim(30,6);assertNotNull(claim);
        assertTrue(STORE.finish(claim,new Result(400,"http_400"),new RetryPolicy(6,1,1,()->0)));
        var properties=settings.properties("dlq-verifier");
        properties.put("key.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        properties.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        properties.put("group.id","dlq-test-"+UUID.randomUUID());properties.put("auto.offset.reset","earliest");properties.put("enable.auto.commit","false");
        try(var verifier=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(properties)) {
            verifier.subscribe(List.of(settings.deadLetterTopic));var pipeline=pipeline(true,false);pipeline.start();
            try {
                var observed=new AtomicReference<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>>();
                await(()->{for(var record:verifier.poll(Duration.ofMillis(200))) if(record.key().equals(claim.id().toString()))observed.set(record);return observed.get()!=null;},20);
                var record=observed.get();var body=JSON.readTree(record.value());
                assertEquals(event.id().toString(),body.path("event").path("id").asText());
                assertEquals(event.id().toString(),new String(record.headers().lastHeader("X-Event-ID").value(),java.nio.charset.StandardCharsets.UTF_8));
                await(()->STORE.deadLetters("demo",10,0).getFirst().publishedAt()!=null,10);
                assertEquals(1,STORE.deadLetters("demo",10,0).size());
            } finally {pipeline.stop();}
        }
    }

    @Test void expiredDeadLetterPublisherCannotAcknowledgeReplacementLease() throws Exception {
        subscription();var event=accept();STORE.activateDispatch("demo",event.id());var delivery=STORE.claim(30,6);
        STORE.finish(delivery,new Result(400,"http_400"),new RetryPolicy(6,1,1,()->0));
        var old=STORE.claimDeadLetter();assertNotNull(old);
        DB.useHandle(h->h.execute("UPDATE delivery_dead_letters SET lease_expires_at=clock_timestamp()-interval '1 second'"));
        var replacement=STORE.claimDeadLetter();assertNotNull(replacement);assertNotEquals(old.token(),replacement.token());
        STORE.finishDeadLetter(old);assertNull(STORE.deadLetters("demo",10,0).getFirst().publishedAt());
        STORE.finishDeadLetter(replacement);assertNotNull(STORE.deadLetters("demo",10,0).getFirst().publishedAt());
    }

}
