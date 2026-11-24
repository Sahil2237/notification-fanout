package com.example.notifications;

import io.dropwizard.lifecycle.Managed;
import jakarta.ws.rs.ServiceUnavailableException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;

/** Atomic fixed windows shared by every application; never falls back to local/SQL counters. */
public final class RedisRateLimiter implements RequestLimiter, Managed, AutoCloseable {
    static final String PREFIX = "notification:limits:v1:";
    static final String SCRIPT = """
            local now = tonumber(redis.call('TIME')[1])
            local window = math.floor(now / 60)
            local windows = {}
            local counts = {}
            for i,key in ipairs(KEYS) do
              local previous = tonumber(redis.call('HGET',key,'window') or '-1')
              windows[i] = math.max(previous,window)
              counts[i] = previous < window and 0 or tonumber(redis.call('HGET',key,'count') or '0')
              if counts[i] >= tonumber(ARGV[i]) then return 0 end
            end
            for i,key in ipairs(KEYS) do
              redis.call('HSET',key,'window',windows[i],'count',counts[i]+1)
              redis.call('EXPIREAT',key,(windows[i]+1)*60)
            end
            return 1
            """;
    private final UnifiedJedis client;
    private final Supplier<Pipeline> pipelines;
    private final RedisSettings settings;

    public RedisRateLimiter(RedisSettings settings) {
        this.settings=settings;
        var pool=new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(settings.poolSize);pool.setMaxIdle(settings.poolSize);pool.setMinIdle(0);
        pool.setBlockWhenExhausted(true);pool.setMaxWait(Duration.ofMillis(settings.timeoutMillis));pool.setFairness(true);
        var config=clientConfig(settings.user,settings.password);
        if (settings.sentinels.isEmpty()) {
            var standalone=RedisClient.builder().hostAndPort(settings.host,settings.port).clientConfig(config).poolConfig(pool).build();
            client=standalone;pipelines=standalone::pipelined;
        } else {
            Set<HostAndPort> addresses=new java.util.HashSet<>();
            for(var endpoint:settings.sentinels) {var parts=endpoint.split(":",2);addresses.add(new HostAndPort(parts[0],Integer.parseInt(parts[1])));}
            var sentinel=RedisSentinelClient.builder().masterName(settings.masterName).sentinels(addresses)
                    .clientConfig(config).sentinelClientConfig(clientConfig(settings.sentinelUser,settings.sentinelPassword)).poolConfig(pool).build();
            client=sentinel;pipelines=sentinel::pipelined;
        }
    }

    private JedisClientConfig clientConfig(String user,String password) {
        var config=DefaultJedisClientConfig.builder().user(user).password(password)
                .connectionTimeoutMillis(settings.timeoutMillis).socketTimeoutMillis(settings.timeoutMillis)
                .clientName("notification-limiter").protocol(RedisProtocol.RESP2);
        if(settings.tls) {
            var pem=settings.caCertificate.isBlank()?System.getenv(settings.caCertificateEnvironment):settings.caCertificate;
            if(pem==null || pem.isBlank()) throw new IllegalArgumentException("Redis CA certificate is required");
            try {
                var certificate=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
                var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("redis",certificate);
                var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
                var context=SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);
                var parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                config.ssl(true).sslSocketFactory(context.getSocketFactory()).sslParameters(parameters);
            } catch(Exception e) {throw new IllegalArgumentException("Cannot configure verified Redis TLS",e);}
        }
        return config.build();
    }

    static String tenantHash(String tenant) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String requestKey(String tenant) {return PREFIX+"{"+tenantHash(tenant)+"}:requests";}
    @Override public boolean allow(String tenant,int limit) {return reserve(List.of(requestKey(tenant)),List.of(Integer.toString(limit)));}
    @Override public boolean allowRetry(String tenant,UUID subscription,int tenantLimit,int subscriptionLimit) {
        var prefix=PREFIX+"{"+tenantHash(tenant)+"}:retry:";
        return reserve(List.of(prefix+"tenant",prefix+subscription),List.of(Integer.toString(tenantLimit),Integer.toString(subscriptionLimit)));
    }
    private boolean reserve(List<String> keys,List<String> limits) {
        try(var pipeline=pipelines.get()) {
            // EVAL and WAIT share the same borrowed connection. WAIT on another pool connection would not protect this write.
            var allowed=pipeline.eval(SCRIPT,keys,limits);
            var replicated=pipeline.waitReplicas(settings.requiredReplicas,settings.replicationWaitMillis);
            pipeline.sync();
            if(((Number)allowed.get()).longValue()==0) return false;
            if(replicated.get()<settings.requiredReplicas) throw new ServiceUnavailableException("Redis replication acknowledgement unavailable");
            return true;
        } catch(ServiceUnavailableException e){throw e;}
        catch(RuntimeException e){throw new ServiceUnavailableException("Rate limiter temporarily unavailable");}
    }
    public int replicaCount() {
        try {
            var reply=client.executeCommand(new CommandObject<>(new CommandArguments(Protocol.Command.ROLE),BuilderFactory.ENCODED_OBJECT_LIST));
            return "master".equals(reply.getFirst()) ? ((List<?>)reply.get(2)).size() : -1;
        }
        catch(RuntimeException e){return -1;}
    }
    @Override public boolean healthy(){return replicaCount()>=settings.requiredReplicas;}
    @Override public void start(){if(!healthy())throw new IllegalStateException("Redis primary/replication unavailable");}
    @Override public void stop(){close();}
    @Override public void close(){client.close();}
}
