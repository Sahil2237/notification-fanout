package com.example.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsBridgeTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void baselinesHistoryThenAccumulatesNewEventsAcrossSourceRestarts() throws Exception {
        var bridge = new MetricsBridge();
        bridge.accept(json.readTree("{\"counters\":{\"events.accepted\":{\"count\":1000}}}"));
        assertEquals(0, bridge.registry().counter("events.accepted").getCount());
        bridge.accept(json.readTree("{\"counters\":{\"events.accepted\":{\"count\":1003}}}"));
        assertEquals(3, bridge.registry().counter("events.accepted").getCount());
        bridge.accept(json.readTree("{\"counters\":{\"events.accepted\":{\"count\":2}}}"));
        assertEquals(5, bridge.registry().counter("events.accepted").getCount());
    }
    @Test void limitsInstrumentsAndDropsMissingOrInvalidGauges() throws Exception {
        var bridge = new MetricsBridge();
        bridge.accept(json.readTree("{\"gauges\":{\"delivery.pending\":{\"value\":7},\"secret.user.id\":{\"value\":99}},\"counters\":{\"events.accepted\":{\"count\":-3}}}"));
        assertEquals(7.0, bridge.registry().getGauges().get("delivery.pending").getValue());
        assertFalse(bridge.registry().getMetrics().containsKey("secret.user.id"));
        bridge.accept(json.readTree("{}"));
        assertNull(bridge.registry().getGauges().get("delivery.pending").getValue());
    }
}
