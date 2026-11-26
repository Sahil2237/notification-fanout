package com.example.notifications;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.lifecycle.Managed;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import redis.clients.jedis.UnifiedJedis;

/** Explicit bounded LRU only for cache entries. Never changes Redis server eviction policy. */
public final class RedisApplicationCache implements ApplicationCache, Managed {
    static final String PREFIX = "notification:cache:v2:";
    private static final List<String> KEYS = List.of(PREFIX+"data",PREFIX+"lru",PREFIX+"expiry",PREFIX+"bytes",PREFIX+"clock");
    static final String SCRIPT = """
        local data,lru,expiry,bytes,clock=unpack(KEYS)
        local now=tonumber(redis.call('TIME')[1])
        local function remove(key)
          local raw=redis.call('HGET',data,key)
          if raw then
            local entry=cjson.decode(raw)
            redis.call('INCRBY',bytes,-entry.size)
            if entry.group~='' then
              local group='notification:cache:v2:group:'..entry.group
              redis.call('ZREM',group,key)
              if redis.call('ZCARD',group)==0 then redis.call('DEL',group) end
            end
          end
          redis.call('HDEL',data,key)
          redis.call('ZREM',lru,key)
          redis.call('ZREM',expiry,key)
        end
        for _,key in ipairs(redis.call('ZRANGEBYSCORE',expiry,'-inf',now,'LIMIT',0,256)) do remove(key) end
        if ARGV[1]=='get' then
          local result={}
          for i=2,#ARGV do
            local raw=redis.call('HGET',data,ARGV[i])
            if raw then
              local entry=cjson.decode(raw)
              if entry.expires>now then
                redis.call('ZADD',lru,redis.call('INCR',clock),ARGV[i])
                if entry.group~='' then redis.call('ZADD','notification:cache:v2:group:'..entry.group,redis.call('GET',clock),ARGV[i]) end
                table.insert(result,entry.value)
              else remove(ARGV[i]);table.insert(result,'') end
            else table.insert(result,'') end
          end
          return result
        end
        if ARGV[1]=='clean' then return 1 end
        local key,value,ttl,group,cap=ARGV[2],ARGV[3],tonumber(ARGV[4]),ARGV[5],tonumber(ARGV[6])
        local maxbytes,maxentries=tonumber(ARGV[7]),tonumber(ARGV[8])
        local size=string.len(key)+string.len(value)+string.len(group)+1024
        if size>65536 or size>maxbytes or cap<1 then return 0 end
        remove(key)
        if group~='' then
          local g='notification:cache:v2:group:'..group
          while redis.call('ZCARD',g)>=cap do remove(redis.call('ZRANGE',g,0,0)[1]) end
        end
        local removed=0
        while tonumber(redis.call('GET',bytes) or '0')+size>maxbytes or redis.call('HLEN',data)>=maxentries do
          local oldest=redis.call('ZRANGE',lru,0,0)[1]
          if not oldest or removed>=256 then return 0 end
          remove(oldest);removed=removed+1
        end
        local seq=redis.call('INCR',clock)
        redis.call('HSET',data,key,cjson.encode({value=value,expires=now+ttl,group=group,size=size}))
        redis.call('INCRBY',bytes,size)
        redis.call('ZADD',lru,seq,key)
        redis.call('ZADD',expiry,now+ttl,key)
        if group~='' then
          local g='notification:cache:v2:group:'..group
          redis.call('ZADD',g,seq,key);redis.call('EXPIRE',g,ttl+60)
        end
        return 1
        """;
    private final UnifiedJedis client;
    private final RedisRateLimiter owner;
    private final java.util.function.Supplier<redis.clients.jedis.Pipeline> pipelines;
    private final CacheSettings settings;
    private final MetricRegistry metrics;
    private final java.util.concurrent.ScheduledExecutorService maintenance=BoundedExecutors.poller("cache-cleanup");
    public RedisApplicationCache(RedisRateLimiter connection,CacheSettings settings,MetricRegistry metrics) {
        this.client=connection.cacheClient();this.pipelines=connection.cachePipelines();this.owner=connection;this.settings=settings;this.metrics=metrics;
    }
    @Override public List<String> get(List<String> keys) {
        if(keys.isEmpty()) return List.of();
        try {
            var args=new ArrayList<String>();args.add("get");args.addAll(keys);
            var reply=(List<?>)client.eval(SCRIPT,KEYS,args);
            var result=reply.stream().map(Object::toString).toList();
            result.forEach(v->metrics.counter(v.isEmpty()?"cache.misses":"cache.hits").inc());
            return result;
        } catch(RuntimeException unavailable) {
            metrics.counter("cache.errors").inc();return keys.stream().map(k->"").toList();
        }
    }
    @Override public void put(String key,String value,int ttl,String group,int cap) {
        try {client.eval(SCRIPT,KEYS,List.of("put",key,value,Integer.toString(ttl),group,Integer.toString(cap),Integer.toString(settings.maxBytes),Integer.toString(settings.maxEntries)));}
        catch(RuntimeException unavailable){metrics.counter("cache.errors").inc();}
    }
    @Override public void putAll(java.util.Map<String,String> values,int ttl,String group,int cap) {
        if(values.isEmpty()) return;
        try(var pipe=pipelines.get()) {
            for(var entry:values.entrySet()) pipe.eval(SCRIPT,KEYS,List.of("put",entry.getKey(),entry.getValue(),Integer.toString(ttl),group,Integer.toString(cap),Integer.toString(settings.maxBytes),Integer.toString(settings.maxEntries)));
            pipe.sync();
        } catch(RuntimeException unavailable){metrics.counter("cache.errors").inc();}
    }
    @Override public void cleanup() {
        try {client.eval(SCRIPT,KEYS,List.of("clean"));}
        catch(RuntimeException unavailable){metrics.counter("cache.errors").inc();}
    }
    @Override public void start(){maintenance.scheduleWithFixedDelay(this::cleanup,60,60,TimeUnit.SECONDS);}
    @Override public void stop() throws InterruptedException {maintenance.shutdownNow();maintenance.awaitTermination(2,TimeUnit.SECONDS);owner.close();}
}
