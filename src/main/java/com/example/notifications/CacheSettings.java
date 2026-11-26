package com.example.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/** Cache is optional acceleration, never acceptance or quota authority. */
public class CacheSettings {
    @JsonProperty public boolean enabled = false;
    @Min(1) @Max(300) @JsonProperty public int eventTtlSeconds = 300;
    @Min(65536) @Max(34359738368L) @JsonProperty public long maxBytes = 33554432L;
    @Min(10) @Max(20000000) @JsonProperty public int maxEntries = 50000;
    @Min(1) @Max(20) @JsonProperty public int subscriptionPercent = 20;
}
