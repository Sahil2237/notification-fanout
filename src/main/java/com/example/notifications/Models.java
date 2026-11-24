package com.example.notifications;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}

    public record EventInput(
            @NotBlank @Size(max = 128) String type,
            @NotBlank @Size(max = 128) String source,
            @NotNull JsonNode payload, UUID eventId) {
        public EventInput(String type, String source, JsonNode payload) { this(type, source, payload, null); }
    }

    public record Identity(String tenant, String userId, String compartmentId) {}

    public record Condition(
            @NotNull @Size(max = 256) String path,
            @NotNull @Pattern(regexp = "eq") String operator,
            @NotNull JsonNode value) {}

    public record Rules(
            @Size(max = 128) String type,
            @Size(max = 128) String source,
            @Valid @Size(max = 20) List<@NotNull Condition> payloadConditions) {
        public Rules {
            payloadConditions =
                    payloadConditions == null ? List.of() : List.copyOf(payloadConditions);
        }

        public static Rules all() {
            return new Rules(null, null, List.of());
        }
    }

    public record SubscriptionInput(
            @NotBlank @Size(max = 2048) String webhookUrl, @Valid Rules filter) {
        public SubscriptionInput {
            filter = filter == null ? Rules.all() : filter;
        }
    }

    public record Event(
            UUID id, String type, String source, JsonNode payload, Instant acceptedAt) {}

    public record Subscription(
            UUID id, String webhookUrl, Rules filter, Instant createdAt, Instant deletedAt) {}

    public record Accepted(Event event, boolean duplicate) {}

    public record Attempt(
            int number,
            Instant startedAt,
            Instant finishedAt,
            Integer httpStatus,
            String outcome,
            String error) {}

    public record Delivery(
            UUID id,
            UUID eventId,
            UUID subscriptionId,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant deliveredAt,
            List<Attempt> attempts) {}

    public record Claim(
            UUID id,
            UUID subscriptionId,
            String tenant,
            String webhookUrl,
            UUID token,
            int number,
            Event event) {}

    public record DeadLetter(UUID deliveryId, UUID eventId, UUID subscriptionId, int attemptCount,
            String reason, Instant failedAt, Instant publishedAt) {}

    public record Result(Integer httpStatus, String error) {}

    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {}
}
