package com.example.notifications;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Restores context even when an executor reuses a thread after an exception. */
public final class EventLog implements AutoCloseable {
    private final Map<String,String> previous = MDC.getCopyOfContextMap();
    public EventLog(UUID eventId, String tenant) {
        MDC.put("event_id", eventId.toString());
        MDC.put("tenant_id", tenant);
    }
    @Override public void close() {
        if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
    }
}
