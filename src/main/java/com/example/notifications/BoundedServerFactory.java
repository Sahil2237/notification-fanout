package com.example.notifications;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.server.DefaultServerFactory;
import io.dropwizard.core.setup.Environment;
import jakarta.validation.constraints.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.QoSHandler;

@JsonTypeName("bounded")
public final class BoundedServerFactory extends DefaultServerFactory {
    @Min(1) @Max(128) @JsonProperty public int activeRequests=32;
    @Min(0) @Max(1024) @JsonProperty public int waitingRequests=128;
    @Min(1) @Max(5000) @JsonProperty public int waitMillis=1000;
    public BoundedServerFactory(){setMinThreads(8);setMaxThreads(64);setAdminMinThreads(1);setAdminMaxThreads(8);}
    @Override public Server build(Environment environment) {
        var server=super.build(environment);var admission=new QoSHandler(server.getHandler());
        admission.setMaxRequestCount(activeRequests);admission.setMaxSuspendedRequestCount(waitingRequests);
        admission.setMaxSuspend(java.time.Duration.ofMillis(waitMillis));admission.excludePath("/health/*");
        environment.metrics().register("http.admission.waiting",(com.codahale.metrics.Gauge<Integer>)admission::getSuspendedRequestCount);
        environment.metrics().register("http.admission.rejected",(com.codahale.metrics.Gauge<Long>)admission::getTotalExceededRequestCount);
        server.setHandler(admission);return server;
    }
}
