package com.example.notifications;
import java.util.UUID;
public interface RequestLimiter {
    boolean allow(String tenant, int limit);
    boolean allowPerSecond(String tenant,int limit);
    boolean allowRetry(String tenant, UUID subscription, int tenantLimit, int subscriptionLimit);
    boolean healthy();
}
