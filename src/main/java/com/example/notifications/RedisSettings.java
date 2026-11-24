package com.example.notifications;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.List;

public class RedisSettings {
    @NotBlank @JsonProperty public String host = "notification-redis";
    @Min(1) @Max(65535) @JsonProperty public int port = 6379;
    @JsonProperty public String user = "default";
    @JsonProperty public String password = "";
    @JsonProperty public boolean tls = false;
    @JsonProperty public String caCertificateEnvironment = "REDIS_CA_CERT";
    @JsonProperty public String caCertificate = "";
    @JsonProperty public List<String> sentinels = List.of();
    @JsonProperty public String masterName = "notification-rate";
    @JsonProperty public String sentinelUser = "discovery";
    @JsonProperty public String sentinelPassword = "";
    @Min(1) @Max(32) @JsonProperty public int poolSize = 8;
    @Min(50) @Max(5000) @JsonProperty public int timeoutMillis = 1000;
    @Min(0) @Max(2) @JsonProperty public int requiredReplicas = 0;
    @Min(1) @Max(2000) @JsonProperty public int replicationWaitMillis = 500;

    public void validate(boolean production, boolean singleHost) {
        if (sentinels == null || sentinels.size() > 3 || sentinels.stream().distinct().count() != sentinels.size()) throw new IllegalArgumentException("At most three distinct Sentinel endpoints are allowed");
        for (var endpoint : sentinels)
            if (!endpoint.matches("[a-zA-Z0-9.-]+:[0-9]{1,5}")
                    || Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':')+1)) < 1
                    || Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':')+1)) > 65535) throw new IllegalArgumentException("Invalid Sentinel endpoint");
        if (production && (password == null || password.length() < 32))
            throw new IllegalArgumentException("Redis needs an independent strong credential");
        if (production && !singleHost && (!tls || sentinels.size() != 3 || requiredReplicas < 1 || (sentinelPassword == null || sentinelPassword.length() < 32)))
            throw new IllegalArgumentException("HA Redis requires verified TLS, three Sentinels, replication acknowledgement and separate Sentinel credentials");
    }
}
