package com.example.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/** Cache is optional acceleration, never acceptance or quota authority. */
public class CacheSettings {
    @JsonProperty public boolean enabled = false;
    @Min(1) @Max(600) @JsonProperty public int eventTtlSeconds = 600;
    @Min(65536) @Max(16777216) @JsonProperty public int maxBytes = 4194304;
    @Min(10) @Max(10000) @JsonProperty public int maxEntries = 10000;
    @Min(1) @Max(20) @JsonProperty public int subscriptionPercent = 20;
}
