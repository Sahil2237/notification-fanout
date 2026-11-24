package com.example.notifications;
import java.util.UUID;
final class TestLimiter implements RequestLimiter {
    public boolean allow(String tenant,int limit){return true;}
    public boolean allowRetry(String tenant,UUID subscription,int tenantLimit,int subscriptionLimit){return true;}
    public boolean healthy(){return true;}
}
