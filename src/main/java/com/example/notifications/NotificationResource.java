package com.example.notifications;

import static com.example.notifications.Models.*;

import com.codahale.metrics.MetricRegistry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class NotificationResource {
    private final ServiceConfiguration config;
    private final Store store;
    private final WebhookPolicy destinations;
    private final MetricRegistry metrics;

    public NotificationResource(Store store, WebhookPolicy destinations, MetricRegistry metrics, ServiceConfiguration config) {
        this.config = config;
        this.store = store;
        this.destinations = destinations;
        this.metrics = metrics;
    }

    @POST
    @Path("/subscriptions")
    public Response subscribe(
            @Context SecurityContext user, @HeaderParam("Idempotency-Key") String key,
            @NotNull @Valid SubscriptionInput input) {
        validateIdempotencyKey(key);
        Filters.validate(input.filter());
        destinations.validate(input.webhookUrl());
        var subscription = store.createSubscription(user.getUserPrincipal().getName(), input, key);
        return Response.created(URI.create("/v1/subscriptions/" + subscription.id()))
                .entity(subscription)
                .build();
    }

    @GET
    @Path("/subscriptions")
    public Page<Subscription> subscriptions(
            @Context SecurityContext user,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        checkPage(limit, offset);
        return page(
                store.subscriptions(user.getUserPrincipal().getName(), limit + 1, offset),
                limit,
                offset);
    }

    @DELETE
    @Path("/subscriptions/{id}")
    public Response delete(@Context SecurityContext user, @PathParam("id") UUID id) {
        store.deleteSubscription(user.getUserPrincipal().getName(), id);
        return Response.noContent().build();
    }

    @POST
    @Path("/events")
    public Response ingest(
            @Context SecurityContext user,
            @HeaderParam("Idempotency-Key") String key,
            @NotNull @Valid EventInput input, @Context jakarta.servlet.http.HttpServletRequest request) {
        validateIdempotencyKey(key);
        try (var ignored = metrics.timer("events.acceptance_latency").time()) {
            var accepted = store.ingest(config.identity(user.getUserPrincipal().getName()), input, key);
            request.setAttribute("eventId", accepted.event().id());
            try (var context = new EventLog(accepted.event().id(),user.getUserPrincipal().getName())) {
                org.slf4j.LoggerFactory.getLogger(NotificationResource.class).info(
                        "event_id={} producer_event_id={} event_accepted duplicate={}",accepted.event().id(),input.eventId(),accepted.duplicate());
            }
            metrics.counter(accepted.duplicate() ? "events.duplicates" : "events.accepted").inc();
            return Response.accepted(accepted)
                    .location(URI.create("/v1/events/" + accepted.event().id()))
                    .build();
        }
    }

    @GET
    @Path("/events/{id}")
    public Event event(@Context SecurityContext user, @PathParam("id") UUID id, @Context jakarta.servlet.http.HttpServletRequest request) {
        request.setAttribute("eventId", id);
        return store.event(user.getUserPrincipal().getName(), id);
    }

    @GET
    @Path("/deliveries/{id}")
    public Delivery delivery(@Context SecurityContext user, @PathParam("id") UUID id, @Context jakarta.servlet.http.HttpServletRequest request) {
        var delivery = store.delivery(user.getUserPrincipal().getName(), id);
        request.setAttribute("eventId", delivery.eventId());
        return delivery;
    }

    @GET
    @Path("/events/{id}/deliveries")
    public Page<Delivery> eventDeliveries(
            @Context SecurityContext user,
            @PathParam("id") UUID id,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset, @Context jakarta.servlet.http.HttpServletRequest request) {
        checkPage(limit, offset);
        request.setAttribute("eventId", id);
        return page(
                store.deliveries(user.getUserPrincipal().getName(), id, true, limit + 1, offset),
                limit,
                offset);
    }

    @GET
    @Path("/subscriptions/{id}/deliveries")
    public Page<Delivery> subscriptionDeliveries(
            @Context SecurityContext user,
            @PathParam("id") UUID id,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        checkPage(limit, offset);
        return page(
                store.deliveries(user.getUserPrincipal().getName(), id, false, limit + 1, offset),
                limit,
                offset);
    }

    @GET
    @Path("/dead-letters")
    public Page<DeadLetter> deadLetters(@Context SecurityContext user,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        checkPage(limit, offset);
        return page(store.deadLetters(user.getUserPrincipal().getName(), limit + 1, offset), limit, offset);
    }

    private static void validateIdempotencyKey(String key) {
        if (key != null && (key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isISOControl)))
            throw new BadRequestException("Idempotency-Key must contain 1..200 characters");
    }

    private static void checkPage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new BadRequestException("limit must be 1..100 and offset 0..100000");
    }

    private static <T> Page<T> page(List<T> values, int limit, int offset) {
        return new Page<>(
                values.subList(0, Math.min(values.size(), limit)),
                limit,
                offset,
                values.size() > limit);
    }
}
