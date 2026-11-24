package com.example.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Optional outbound metrics only. No logs, traces, alarms, or ticket integration. */
public final class MetricsSettings {
    @JsonProperty public boolean enabled = false;
    @JsonProperty public String endpoint = "https://otlp-insights-blr1.digitalocean.com/v1/metrics";
    @JsonProperty public String tokenEnvironment = "DO_METRICS_INGEST_TOKEN";
    @JsonProperty public String serviceName = "notification-fanout";
    @JsonProperty public String dropletId = "";
    @JsonProperty public String teamId = "";
    @JsonProperty public String region = "blr1";
    @JsonProperty public int intervalSeconds = 60;

    public void validate() {
        if (!enabled) return;
        if (!region.matches("[a-z]{3}[0-9]")) throw new IllegalArgumentException("Invalid metrics region");
        if (!endpoint.equals("https://otlp-insights-" + region + ".digitalocean.com/v1/metrics"))
            throw new IllegalArgumentException("Metrics endpoint must match the DigitalOcean resource region");
        if (!dropletId.matches("[0-9]{1,20}")
                || !teamId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Metrics require a Droplet ID and DigitalOcean team UUID");
        if (!serviceName.matches("[a-zA-Z][a-zA-Z0-9.-]{0,62}") || intervalSeconds < 30 || intervalSeconds > 300)
            throw new IllegalArgumentException("Invalid metrics service or interval");
        if (tokenEnvironment == null || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid metrics token environment variable");
        token();
    }

    public String token() {
        var token = System.getenv(tokenEnvironment);
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("Metrics ingest token is missing or invalid");
        return token;
    }
}
