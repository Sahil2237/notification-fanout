package com.example.notifications;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Priority(Priorities.AUTHENTICATION)
public final class ApiSecurity implements ContainerRequestFilter, ContainerResponseFilter {
    private final ServiceConfiguration config;
    private final Clock clock;
    private final RequestLimiter limiter;
    private final com.codahale.metrics.MetricRegistry metrics;
    private final Map<String, Window> windows = new HashMap<>();
    private static final int MAX_BODY = 256 * 1024;

    private record Window(long minute, int count) {}

    public ApiSecurity(ServiceConfiguration config, Clock clock) {
        this(config, clock, null);
    }
    public ApiSecurity(ServiceConfiguration config, Clock clock, RequestLimiter limiter) {
        this(config, clock, limiter, new com.codahale.metrics.MetricRegistry());
    }
    public ApiSecurity(ServiceConfiguration config, Clock clock, RequestLimiter limiter, com.codahale.metrics.MetricRegistry metrics) {
        this.metrics = metrics;
        if (config.production && limiter == null) throw new IllegalArgumentException("Production requires the shared rate limiter");
        this.config = config; this.clock = clock; this.limiter = limiter;
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        var supplied = request.getHeaderString("X-Request-ID");
        var id =
                supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
                        ? supplied
                        : UUID.randomUUID().toString();
        request.setProperty("requestId", id);
        var path = request.getUriInfo().getPath();
        if (path.equals("health/live") || path.equals("health/ready")) return;
        var authorization = request.getHeaderString("Authorization");
        var token =
                authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7)
                        : "";
        String tenant = null;
        for (var entry : config.credentials.entrySet()) {
            if (MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    entry.getValue().getBytes(StandardCharsets.UTF_8))) tenant = entry.getKey();
        }
        var adminRequest = path.startsWith("v1/admin/");
        if (adminRequest) {
            var validAdmin = !config.adminToken.isEmpty() && MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), config.adminToken.getBytes(StandardCharsets.UTF_8));
            if (!validAdmin) {
                reject(request, tenant == null ? 401 : 403, "administrator_required", "Administrator credentials required");
                return;
            }
            tenant = "__administrator__";
        }
        if (tenant == null) {
            reject(request, 401, "unauthorized", "A valid bearer token is required");
            return;
        }
        request.setProperty("rateWindowSeconds",config.requestsPerSecond>0?1:60);
        request.setProperty("rateLimit",config.requestsPerSecond>0?config.requestsPerSecond:config.requestsPerMinute);
        if (!allow(tenant)) {
            reject(request, 429, "rate_limited", "Per-tenant request limit exceeded");
            return;
        }
        var identity = tenant;
        request.setSecurityContext(
                new SecurityContext() {
                    public Principal getUserPrincipal() {
                        return () -> identity;
                    }

                    public boolean isUserInRole(String role) {
                        return (adminRequest ? "administrator" : "tenant").equals(role);
                    }

                    public boolean isSecure() {
                        return "https".equals(request.getUriInfo().getRequestUri().getScheme());
                    }

                    public String getAuthenticationScheme() {
                        return "Bearer";
                    }
                });
        if (request.hasEntity()) {
            var body = request.getEntityStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                reject(request, 413, "payload_too_large", "Maximum body size is 256 KiB");
                return;
            }
            request.setEntityStream(new ByteArrayInputStream(body));
        }
    }

    boolean allow(String tenant) {
        if (limiter != null) return config.requestsPerSecond>0?limiter.allowPerSecond(tenant,config.requestsPerSecond):limiter.allow(tenant,config.requestsPerMinute);
        synchronized (windows) {
        var minute = clock.millis() / (config.requestsPerSecond>0?1000:60000);
        var previous = windows.get(tenant);
        var count = previous == null || previous.minute() != minute ? 0 : previous.count();
        if (count >= (config.requestsPerSecond>0?config.requestsPerSecond:config.requestsPerMinute)) return false;
        windows.put(tenant, new Window(minute, count + 1));
        return true;
        }
    }

    private static void reject(
            ContainerRequestContext request, int status, String code, String message) {
        var response =
                Response.status(status)
                        .type("application/json")
                        .entity(
                                Map.of(
                                        "code",
                                        code,
                                        "message",
                                        message,
                                        "requestId",
                                        request.getProperty("requestId")));
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        if (status == 429) response.header("Retry-After",String.valueOf(request.getProperty("rateWindowSeconds")));
        request.abortWith(response.build());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!request.getUriInfo().getPath().startsWith("health/")) {
            var status = response.getStatus();
            metrics.counter(status >= 500 ? "http.server.errors" : status >= 400 ? "http.client.errors" : "http.success").inc();
            if (status == 429) metrics.counter("http.rate.limited").inc();
        }
        response.getHeaders().putSingle("X-Request-ID", request.getProperty("requestId"));
        if(request.getProperty("rateLimit")!=null) {
            response.getHeaders().putSingle("X-RateLimit-Limit",request.getProperty("rateLimit"));
            response.getHeaders().putSingle("X-RateLimit-Window-Seconds",request.getProperty("rateWindowSeconds"));
        }
        response.getHeaders().putSingle("Cache-Control", "no-store");
        var eventId = request.getProperty("eventId");
        if (eventId != null) response.getHeaders().putSingle("X-Event-ID", eventId.toString());
        if ("GET".equals(request.getMethod()) && request.getUriInfo().getPath().startsWith("v1/") && !request.getUriInfo().getPath().startsWith("v1/admin/")) {
            response.getHeaders().putSingle("X-Read-Consistency", "eventual");
            response.getHeaders().putSingle("X-Read-Source", config.readDatabase == null ? "primary" : "replica");
        }
        var principal = request.getSecurityContext().getUserPrincipal();
        try (var context = eventId == null ? null : new EventLog(UUID.fromString(eventId.toString()),principal == null ? "unknown" : principal.getName())) {
        LoggerFactory.getLogger(ApiSecurity.class)
                .info(
                        "request_id={} event_id={} method={} status={}",
                        request.getProperty("requestId"), eventId,
                        request.getMethod(),
                        response.getStatus());
        }
    }
}
