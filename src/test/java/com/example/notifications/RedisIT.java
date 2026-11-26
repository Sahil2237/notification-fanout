package com.example.notifications;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import redis.clients.jedis.RedisClient;

class RedisIT {
    private RedisSettings settings() {
        var config=new RedisSettings();config.host=System.getenv().getOrDefault("TEST_REDIS_HOST","notification-redis");
        if(!List.of("notification-redis","localhost","127.0.0.1","redis").contains(config.host))
            throw new IllegalStateException("Disposable Redis fixture only");
        return config;
    }
    @Test void fixedWindowIsAtomicAcrossIndependentInstancesAndHasExpiry() throws Exception {
        var config=settings();var tenant="test-"+UUID.randomUUID();var allowed=new AtomicInteger();
        try(var a=new RedisRateLimiter(config);var b=new RedisRateLimiter(config);var admin=RedisClient.create(config.host,config.port)) {
            var windowBefore=((Number)admin.eval("return tonumber(redis.call('TIME')[1])",List.of(),List.of())).longValue()/60;
            try(var workers=Executors.newFixedThreadPool(12)) {
                var tasks=new java.util.ArrayList<Future<?>>();
                for(var n=0;n<100;n++) {var limiter=n%2==0?a:b;tasks.add(workers.submit(()->{if(limiter.allow(tenant,20))allowed.incrementAndGet();}));}
                for(var task:tasks)task.get(10,TimeUnit.SECONDS);
            }
            var windowAfter=((Number)admin.eval("return tonumber(redis.call('TIME')[1])",List.of(),List.of())).longValue()/60;
            org.junit.jupiter.api.Assumptions.assumeTrue(windowBefore==windowAfter,"Window changed during concurrency test");
            assertEquals(20,allowed.get());assertFalse(b.allow(tenant,20));assertTrue(b.allow(tenant+"-other",20));
            var key=RedisRateLimiter.requestKey(tenant);assertTrue(admin.ttl(key)>0 && admin.ttl(key)<=60);
            admin.hset(key,"window",Long.toString(windowAfter-1));
            assertTrue(a.allow(tenant,20));assertEquals("1",admin.hget(key,"count"));
            admin.hset(key,java.util.Map.of("window",Long.toString(windowAfter+1),"count","20"));
            assertFalse(a.allow(tenant,20));
        }
    }
    @Test void fiveTenantsHaveIndependentThousandPerSecondQuotaAcrossInstances() throws Exception {
        var config=settings();var prefix="five-"+UUID.randomUUID();
        try(var a=new RedisRateLimiter(config);var b=new RedisRateLimiter(config);var admin=RedisClient.create(config.host,config.port)) {
            // Disposable fixture: pin the clock-rollback window ahead to prevent second-boundary flakiness.
            var now=((Number)admin.eval("return tonumber(redis.call('TIME')[1])",List.of(),List.of())).longValue();
            for(var tenant=0;tenant<5;tenant++)admin.hset(RedisRateLimiter.secondKey(prefix+tenant),java.util.Map.of("window",Long.toString(now+60),"count","0"));
            var executor=new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16),new ThreadPoolExecutor.CallerRunsPolicy());
            var accepted=new AtomicInteger();
            try(executor) {
                var tasks=new java.util.ArrayList<Future<?>>();
                for(var i=0;i<5000;i++){var tenant=prefix+(i%5);var limiter=i%2==0?a:b;tasks.add(executor.submit(()->{if(limiter.allowPerSecond(tenant,1000))accepted.incrementAndGet();}));}
                for(var task:tasks)task.get(20,TimeUnit.SECONDS);
            }
            assertEquals(5000,accepted.get());
            for(var tenant=0;tenant<5;tenant++) {
                assertFalse(b.allowPerSecond(prefix+tenant,1000));
                assertTrue(a.allow(prefix+tenant,1),"Minute/retry budgets use separate namespaces");
                var key=RedisRateLimiter.secondKey(prefix+tenant);
                admin.hset(key,java.util.Map.of("window",Long.toString(now-1),"count","1000"));
                assertTrue(a.allowPerSecond(prefix+tenant,1000));assertEquals("1",admin.hget(key,"count"));
                assertTrue(admin.ttl(key)>=0 && admin.ttl(key)<=1);
                admin.del(key);admin.del(RedisRateLimiter.requestKey(prefix+tenant));
            }
        }
    }
    @Test void retryBudgetsReserveTenantAndSubscriberAtomically() {
        var config=settings();var tenant="retry-"+UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        try(var limiter=new RedisRateLimiter(config)) {
            assertTrue(limiter.allowRetry(tenant,a,3,1));assertFalse(limiter.allowRetry(tenant,a,3,1));
            assertTrue(limiter.allowRetry(tenant,b,3,3));assertTrue(limiter.allowRetry(tenant,b,3,3));
            assertFalse(limiter.allowRetry(tenant,b,3,3));assertTrue(limiter.allowRetry(tenant+"-other",a,3,1));
        }
    }
    @Test void missingReplicationAcknowledgementFailsClosed() {
        var config=settings();config.requiredReplicas=1;config.replicationWaitMillis=20;
        try(var limiter=new RedisRateLimiter(config)) {
            assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,()->limiter.allow("wait-"+UUID.randomUUID(),10));
            assertFalse(limiter.healthy());
        }
    }
    @Test void unavailableRedisDoesNotFallBackToLocalOrSqlCounters() {
        var config=settings();config.port=1;config.timeoutMillis=50;
        try(var limiter=new RedisRateLimiter(config)) {
            assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,()->limiter.allow("unavailable",10));
            assertFalse(limiter.healthy());
        }
    }
}
