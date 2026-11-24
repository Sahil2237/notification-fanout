package com.example.notifications;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

import org.jdbi.v3.core.JdbiException;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

public final class ApiErrors implements ExceptionMapper<Exception> {
    @Inject private Provider<ContainerRequestContext> request;

    @Override
    public Response toResponse(Exception error) {
        var status =
                error instanceof WebApplicationException web
                        ? web.getResponse().getStatus()
                        : error instanceof IllegalArgumentException
                                ? 400
                                : error instanceof JdbiException ? 503 : 500;
        var message =
                status == 500
                        ? "Internal server error"
                        : status == 503
                                ? "Persistence temporarily unavailable"
                                : status == 404 ? "Resource not found" : error.getMessage();
        if (message == null) message = "Request could not be processed";
        if (status >= 500)
            LoggerFactory.getLogger(ApiErrors.class)
                    .warn(
                            "api_failure status={} error_type={} failure_location={}",
                            status,
                            error.getClass().getSimpleName(), error.getStackTrace().length == 0 ? "unknown" : error.getStackTrace()[0]);
        var requestId =
                request == null
                        ? UUID.randomUUID().toString()
                        : String.valueOf(request.get().getProperty("requestId"));
        return Response.status(status)
                .type("application/json")
                .entity(
                        Map.of(
                                "code",
                                "http_" + status,
                                "message",
                                message,
                                "requestId",
                                requestId))
                .build();
    }
}
