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
    private final Map<String, Window> windows = new HashMap<>();
    private static final int MAX_BODY = 256 * 1024;

    private record Window(long minute, int count) {}

    public ApiSecurity(ServiceConfiguration config, Clock clock) {
        this.config = config;
        this.clock = clock;
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

    synchronized boolean allow(String tenant) {
        var minute = clock.millis() / 60000;
        var previous = windows.get(tenant);
        var count = previous == null || previous.minute() != minute ? 0 : previous.count();
        if (count >= config.requestsPerMinute) return false;
        windows.put(tenant, new Window(minute, count + 1));
        return true;
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
        if (status == 429) response.header("Retry-After", "60");
        request.abortWith(response.build());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("X-Request-ID", request.getProperty("requestId"));
        response.getHeaders().putSingle("Cache-Control", "no-store");
        LoggerFactory.getLogger(ApiSecurity.class)
                .info(
                        "request_id={} method={} status={}",
                        request.getProperty("requestId"),
                        request.getMethod(),
                        response.getStatus());
    }
}
