package com.example.notifications;

import static com.example.notifications.Models.*;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

public final class Filters {
    private Filters() {}

    public static void validate(Rules rules) {
        for (var condition : rules.payloadConditions()) {
            if (!condition.path().isEmpty() && !condition.path().startsWith("/")) {
                throw new IllegalArgumentException("Payload paths must use JSON Pointer syntax");
            }
            if (condition.path().matches(".*~(?![01]).*")) {
                throw new IllegalArgumentException("Invalid JSON Pointer escape");
            }
            JsonPointer.compile(condition.path());
            if (!"eq".equals(condition.operator())) {
                throw new IllegalArgumentException("Only the eq operator is supported");
            }
        }
    }

    public static boolean matches(Rules rules, EventInput event) {
        if (rules.type() != null && !rules.type().equals(event.type())) return false;
        if (rules.source() != null && !rules.source().equals(event.source())) return false;
        for (var condition : rules.payloadConditions()) {
            var actual = event.payload().at(condition.path());
            if (actual.isMissingNode() || !equal(actual, condition.value())) return false;
        }
        return true;
    }

    private static boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }
}
