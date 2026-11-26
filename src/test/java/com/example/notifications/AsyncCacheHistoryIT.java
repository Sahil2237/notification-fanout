package com.example.notifications;

import static com.example.notifications.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.jackson.Jackson;
import jakarta.ws.rs.WebApplicationException;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import redis.clients.jedis.RedisClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class AsyncCacheHistoryIT {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=Jackson.newObjectMapper();
    static final Jdbi DB=Jdbi.create(System.getenv().getOrDefault("TEST_DB_URL","jdbc:postgresql://notification-db:5432/notifications_test"),
            System.getenv().getOrDefault("DB_USER","notifications"),System.getenv().getOrDefault("DB_PASSWORD","local-only-change-me"));
    Store store; RedisRateLimiter connection; RedisApplicationCache cache; RedisClient admin; CacheSettings settings;
    String tenant;
    @BeforeAll static void migrate() throws Exception {new NotificationApplication().run("db","migrate","config/test.yml");}
    @BeforeEach void prepare() {
        DB.useHandle(h->h.execute("TRUNCATE events,subscriptions CASCADE"));
        tenant="async-"+UUID.randomUUID();
        var redis=new RedisSettings();redis.host=System.getenv().getOrDefault("TEST_REDIS_HOST","notification-redis");
        if(!List.of("notification-redis","redis","localhost","127.0.0.1").contains(redis.host)) throw new IllegalStateException("Disposable Redis fixture only");
        settings=new CacheSettings();settings.enabled=true;
        connection=new RedisRateLimiter(redis);cache=new RedisApplicationCache(connection,settings,new MetricRegistry());
        admin=RedisClient.create(redis.host,redis.port);
        // Guarded disposable fixture only; never touch quota keys or unrelated Redis data.
        for(var key:admin.keys(RedisApplicationCache.PREFIX+"*"))admin.del(key);
        store=new Store(DB,JSON,true);store.configureCache(cache,settings);
    }
    @AfterEach void close() throws Exception {cache.stop();admin.close();}
    EventInput input(UUID id) {return new EventInput("order.created","checkout",JSON.createObjectNode().put("amount",10),id);}
    Subscription subscribe() {return store.createSubscription(tenant,new SubscriptionInput("http://127.0.0.1:19090/hook",Rules.all()));}
    Accepted accept(){return store.ingest(tenant,input(UUID.randomUUID()),UUID.randomUUID().toString());}
    long count(String table){return DB.withHandle(h->h.createQuery("SELECT count(*) FROM "+table).mapTo(Long.class).one());}

    @Test void outboxBatchClaimsAreDisjointAndReplayAfterUncertainPublishIsSafe() {
        var ids=new java.util.HashSet<UUID>();for(var i=0;i<8;i++)ids.add(accept().event().id());
        var a=store.claimOutboxBatch(4);var b=store.claimOutboxBatch(4);
        assertEquals(4,a.size());assertEquals(4,b.size());
        var claimed=new java.util.HashSet<UUID>();a.forEach(v->assertTrue(claimed.add(v.eventId())));b.forEach(v->assertTrue(claimed.add(v.eventId())));
        assertEquals(ids,claimed);store.finishPublishBatch(a,true);store.finishPublishBatch(b,false);
        assertEquals(4,store.outboxPending());assertThrows(IllegalArgumentException.class,()->store.claimOutboxBatch(33));
    }

    @Test void matchingMovesAfterKafkaAndRegistrationBeforeProcessingIsEligible() {
        var event=accept().event();assertEquals(0,count("deliveries"));assertNull(store.claim(30,6));
        subscribe();store.activateDispatch(tenant,event.id());assertEquals(1,count("deliveries"));
        subscribe();store.activateDispatch(tenant,event.id());assertEquals(1,count("deliveries"),"Replay must not rematch newer subscriptions");
    }
    @Test void deletedBeforeMatchingIsExcludedButExistingJobsAreRetained() {
        var first=subscribe();var second=subscribe();var event=accept().event();
        store.deleteSubscription(tenant,first.id());store.activateDispatch(tenant,event.id());
        assertEquals(1,count("deliveries"));store.deleteSubscription(tenant,second.id());
        assertNotNull(store.claim(30,6));
    }
    @Test void concurrentConsumersCreateOneLogicalDelivery() throws Exception {
        subscribe();var event=accept().event();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(var i=0;i<24;i++) futures.add(pool.submit(()->store.activateDispatch(tenant,event.id())));
            for(var f:futures)f.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,count("deliveries"));assertEquals(1,count("delivery_identities"));
    }
    @Test void concurrentRequestsShareRedisAndDatabaseIdentity() throws Exception {
        var event=input(UUID.randomUUID());var key=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<Accepted>>();
            for(var i=0;i<24;i++)jobs.add(pool.submit(()->store.ingest(tenant,event,key)));
            var ids=new java.util.HashSet<UUID>();for(var f:jobs)ids.add(f.get(10,TimeUnit.SECONDS).event().id());
            assertEquals(1,ids.size());
        }
        assertEquals(1,count("events"));assertEquals(1,count("event_outbox"));assertEquals(0,count("deliveries"));
    }
    @Test void cachedProducerAndExplicitKeysEnforceContentAndTenantIsolation() {
        var input=input(UUID.randomUUID());var key=UUID.randomUUID().toString();var first=store.ingest(tenant,input,key);
        assertTrue(store.ingest(tenant,input,key).duplicate());assertEquals(first.event().id(),store.ingest(tenant,input,"another-key").event().id());
        var changed=new EventInput("changed",input.source(),input.payload(),input.eventId());
        assertEquals(409,assertThrows(WebApplicationException.class,()->store.ingest(tenant,changed,key)).getResponse().getStatus());
        assertFalse(store.ingest(tenant+"other",input,key).duplicate());
        assertFalse(store.ingest(tenant,input(UUID.randomUUID()),"separate-occurrence").duplicate());
    }
    @Test void expiryAfterTenMinutesDoesNotExpirePostgresDeduplication() throws Exception {
        var event=input(UUID.randomUUID());var key=UUID.randomUUID().toString();var first=store.ingest(tenant,event,key);
        var fields=admin.hgetAll(RedisApplicationCache.PREFIX+"data");
        for(var field:fields.entrySet()) if(field.getKey().startsWith("event:"+RedisRateLimiter.tenantHash(tenant))) {
            var value=JSON.readTree(field.getValue());var expires=value.path("expires").asLong();
            assertTrue(expires-Instant.now().getEpochSecond()<=600);
            ((com.fasterxml.jackson.databind.node.ObjectNode)value).put("expires",Instant.now().getEpochSecond()-1);
            admin.hset(RedisApplicationCache.PREFIX+"data",field.getKey(),value.toString());
        }
        assertEquals(first.event().id(),store.ingest(tenant,event,key).event().id());assertEquals(1,count("events"));
    }
    @Test void redisFailureFallsBackToPostgresForIdentityAndSubscriptions() throws Exception {
        cache.stop();var broken=new RedisSettings();broken.host="127.0.0.1";broken.port=1;broken.timeoutMillis=50;
        connection=new RedisRateLimiter(broken);cache=new RedisApplicationCache(connection,settings,new MetricRegistry());store.configureCache(cache,settings);
        subscribe();var input=input(UUID.randomUUID());var key="outage";var first=store.ingest(tenant,input,key);
        assertEquals(first.event().id(),store.ingest(tenant,input,key).event().id());store.activateDispatch(tenant,first.event().id());assertEquals(1,count("deliveries"));
    }
    @Test void subscriptionCacheUsesTwentyPercentAndVersionedDeletion() {
        var subscriptions=new java.util.ArrayList<Subscription>();for(var i=0;i<10;i++)subscriptions.add(subscribe());
        var group=RedisRateLimiter.tenantHash(tenant)+":10";
        assertEquals(2,admin.zcard(RedisApplicationCache.PREFIX+"group:"+group));
        var event=accept().event();store.deleteSubscription(tenant,subscriptions.getFirst().id());
        store.activateDispatch(tenant,event.id());assertEquals(9,count("deliveries"));
        var generation=DB.withHandle(h->h.createQuery("SELECT version FROM subscription_versions WHERE tenant_id=:t").bind("t",tenant).mapTo(Long.class).one());
        assertEquals(11,generation);assertTrue(admin.zcard(RedisApplicationCache.PREFIX+"group:"+RedisRateLimiter.tenantHash(tenant)+":11")<=1);
    }
    @Test void cacheLruEvictsOnlyCacheEntriesAndRetainsQuotas() {
        var group="test-lru-"+UUID.randomUUID();assertTrue(connection.allow(tenant,1));
        cache.put("a"+group,"one",600,group,2);cache.put("b"+group,"two",600,group,2);
        assertEquals("one",cache.get(List.of("a"+group)).getFirst());
        cache.put("c"+group,"three",600,group,2);
        assertEquals(List.of("one","","three"),cache.get(List.of("a"+group,"b"+group,"c"+group)));
        assertFalse(connection.allow(tenant,1));
    }
    @Test void cacheEntryAndByteBoundsAreEnforced() {
        settings.maxEntries=10;settings.maxBytes=65536;
        for(var i=0;i<100;i++)cache.put("bound-"+i,"x".repeat(2000),600,"",Integer.MAX_VALUE);
        assertTrue(admin.hlen(RedisApplicationCache.PREFIX+"data")<=10);
        assertTrue(Long.parseLong(admin.get(RedisApplicationCache.PREFIX+"bytes"))<=65536);
    }
    @Test void identityEntriesRemainUntilExpiryWhenSubscriptionLruIsUnderPressure() {
        settings.maxEntries=3;
        cache.put("identity-a","event-a",600,"",Integer.MAX_VALUE);
        cache.put("identity-b","event-b",600,"",Integer.MAX_VALUE);
        cache.put("sub-a","rule-a",600,"rules",2);
        cache.put("sub-b","rule-b",600,"rules",2);
        assertEquals(List.of("event-a","event-b","","rule-b"),cache.get(List.of("identity-a","identity-b","sub-a","sub-b")));
        cache.put("identity-c","event-c",600,"",Integer.MAX_VALUE);
        cache.put("identity-d","event-d",600,"",Integer.MAX_VALUE);
        assertEquals(List.of("event-a","event-b","event-c",""),cache.get(List.of("identity-a","identity-b","identity-c","identity-d")));
    }
    @Test void identityReadsDoNotExtendTheOriginalExpiryOrJoinLru() throws Exception {
        var key="identity-fixed-window";cache.put(key,"original",600,"",Integer.MAX_VALUE);
        var before=JSON.readTree(admin.hget(RedisApplicationCache.PREFIX+"data",key)).path("expires").asLong();
        cache.get(List.of(key));cache.get(List.of(key));
        var after=JSON.readTree(admin.hget(RedisApplicationCache.PREFIX+"data",key)).path("expires").asLong();
        assertEquals(before,after);assertNull(admin.zscore(RedisApplicationCache.PREFIX+"lru",key));
    }
    @Test void legacyLruIdentityIsProtectedDuringRollingUpgrade() throws Exception {
        settings.maxEntries=2;
        cache.put("legacy-identity","original",600,"",Integer.MAX_VALUE);
        admin.zadd(RedisApplicationCache.PREFIX+"lru",0,"legacy-identity");
        cache.put("another-identity","second",600,"",Integer.MAX_VALUE);
        cache.put("third-identity","third",600,"",Integer.MAX_VALUE);
        assertEquals(List.of("original","second",""),cache.get(List.of("legacy-identity","another-identity","third-identity")));
    }

    @Test void failedMatchingRollsBackAndCanBeRetried() {
        var sub=subscribe();var event=accept().event();
        DB.useHandle(h->h.createUpdate("UPDATE subscriptions SET filter='{}'::jsonb || '{\"payloadConditions\":\"invalid\"}'::jsonb WHERE id=:id").bind("id",sub.id()).execute());
        assertThrows(RuntimeException.class,()->store.activateDispatch(tenant,event.id()));assertEquals(0,count("deliveries"));
        DB.useHandle(h->h.createUpdate("UPDATE subscriptions SET filter='{}'::jsonb WHERE id=:id").bind("id",sub.id()).execute());
        store.activateDispatch(tenant,event.id());assertEquals(1,count("deliveries"));
    }
    @Test void archivedDeliveryRemainsQueryableWithAttemptsAndCannotBeRecreated() {
        subscribe();var event=accept().event();store.activateDispatch(tenant,event.id());var claim=store.claim(30,6);
        assertTrue(store.finish(claim,new Result(204,null),new RetryPolicy(6,1,10,()->0.5)));
        assertEquals(1,store.archiveCompleted(0,100));assertEquals(0,count("deliveries"));assertEquals(1,count("delivery_history"));
        var delivery=store.delivery(tenant,claim.id());assertEquals("delivered",delivery.status());assertEquals(204,delivery.attempts().getFirst().httpStatus());
        assertEquals(1,store.deliveries(tenant,event.id(),true,10,0).size());
        store.activateDispatch(tenant,event.id());assertEquals(0,count("deliveries"));
        DB.useHandle(h->h.createUpdate("INSERT INTO deliveries(id,tenant_id,event_id,subscription_id,webhook_url) VALUES(:id,:tenant,:event,:sub,'https://example.com') ON CONFLICT DO NOTHING")
                .bind("id",UUID.randomUUID()).bind("tenant",tenant).bind("event",event.id()).bind("sub",claim.subscriptionId()).execute());
        assertEquals(0,count("deliveries"));assertThrows(jakarta.ws.rs.NotFoundException.class,()->store.delivery(tenant+"other",claim.id()));
    }
    @Test void failedDeliveryArchivePreservesDlqAndPendingJobsStayActive() {
        subscribe();var event=accept().event();store.activateDispatch(tenant,event.id());var claim=store.claim(30,6);
        assertTrue(store.finish(claim,new Result(400,null),new RetryPolicy(6,1,10,()->0.5)));
        var pending=accept().event();store.activateDispatch(tenant,pending.id());
        assertEquals(1,store.archiveCompleted(0,100));assertEquals(1,count("deliveries"));assertEquals(1,store.deadLetters(tenant,10,0).size());
        assertNotNull(store.claimDeadLetter());assertEquals("failed",store.delivery(tenant,claim.id()).status());assertNotNull(store.claim(30,6));
    }
}
