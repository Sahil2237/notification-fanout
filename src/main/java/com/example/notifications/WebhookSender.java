package com.example.notifications;

import static com.example.notifications.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSender implements AutoCloseable {
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper json;
    private final WebhookPolicy destinations;
    private final ServiceConfiguration config;
    public WebhookSender(ObjectMapper json,WebhookPolicy destinations,ServiceConfiguration config) {
        this.json=json;this.destinations=destinations;this.config=config;
        var connections=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(destinations.resolver())
            .setMaxConnTotal(config.workerThreads).setMaxConnPerRoute(config.workerThreads)
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(2))
                .setSocketTimeout(Timeout.ofSeconds(config.requestTimeoutSeconds)).build()).build();
        client=HttpClients.custom().setConnectionManager(connections).disableRedirectHandling().disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofSeconds(config.requestTimeoutSeconds)).build()).build();
    }
    public Result send(Claim claim) {
        var expired=new AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> deadline=null;
        HttpPost request=null;
        try {
            request=new HttpPost(destinations.validate(claim.webhookUrl()));
            var body=json.createObjectNode().put("schemaVersion",1).put("deliveryId",claim.id().toString())
                .put("subscriptionId",claim.subscriptionId().toString());
            body.set("event",json.valueToTree(claim.event()));
            var data=json.writeValueAsString(body);
            var timestamp=Long.toString(Instant.now().getEpochSecond());
            var tenantKey=hmac(config.signingSecret.getBytes(StandardCharsets.UTF_8),"tenant:"+claim.tenant());
            request.setHeader("X-Delivery-ID",claim.id().toString());request.setHeader("X-Event-ID",claim.event().id().toString());
            request.setHeader("X-Webhook-Timestamp",timestamp);
            request.setHeader("X-Webhook-Signature","v1="+HexFormat.of().formatHex(hmac(tenantKey,timestamp+"."+data)));
            request.setEntity(new StringEntity(data,ContentType.APPLICATION_JSON));
            var active=request;
            deadline=deadlines.schedule(()->{expired.set(true);active.cancel();},config.requestTimeoutSeconds,TimeUnit.SECONDS);
            try(var response=client.executeOpen(null,request,null)) {
                var code=response.getCode();
                // Never drain an arbitrary receiver body. Closing this attempt sacrifices reuse.
                request.cancel();
                return new Result(code,code>=200 && code<300?null:"http_"+code);
            }
        } catch(IllegalArgumentException e){return new Result(null,"destination_policy");}
        catch(Exception e){return new Result(null,expired.get()?"timeout":"transport_error");}
        finally{if(deadline!=null)deadline.cancel(false);if(request!=null)request.cancel();}
    }
    static byte[] hmac(byte[] secret, String text) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void close(){deadlines.shutdownNow();client.close(CloseMode.IMMEDIATE);}
}
