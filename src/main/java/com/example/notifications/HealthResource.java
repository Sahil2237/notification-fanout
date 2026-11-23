package com.example.notifications;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/health")
@Produces("application/json")
public final class HealthResource {
    private final Store store;
    private final DeliveryWorker worker;
    private final KafkaPipeline pipeline;

    public HealthResource(Store store, DeliveryWorker worker, KafkaPipeline pipeline) {
        this.store = store;
        this.worker = worker;
        this.pipeline=pipeline;
    }

    @GET
    @Path("/live")
    public Map<String, String> live() {
        return Map.of("status", "alive");
    }

    @GET
    @Path("/ready")
    public Response ready() {
        var ready = store.healthy() && (worker == null || worker.healthy()) && (pipeline == null || pipeline.healthy());
        return Response.status(ready ? 200 : 503)
                .entity(Map.of("status", ready ? "ready" : "unavailable"))
                .build();
    }
}
