package com.example.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

public class ServiceConfiguration extends Configuration {
    @Valid @NotNull @JsonProperty public DataSourceFactory database = new DataSourceFactory();
    @NotEmpty @JsonProperty public Map<String, String> credentials = Map.of();
    @NotEmpty @JsonProperty public List<String> allowedWebhookOrigins = List.of();
    @JsonProperty public boolean allowLocalWebhooks = false;

    @NotBlank
    @Size(min = 24)
    @JsonProperty
    public String signingSecret;

    @JsonProperty public String databaseCaCertificateEnvironment = "PG_CA_CERT";
    private java.nio.file.Path databaseCaFile;

    public void prepareDatabaseSecurity() {
        if(!production) return;
        var certificate=System.getenv(databaseCaCertificateEnvironment);
        if(certificate==null || certificate.isBlank()) throw new IllegalArgumentException("Production database CA certificate is required");
        try {
            if(databaseCaFile==null) {
                databaseCaFile=java.nio.file.Files.createTempFile("notification-db-ca-",".pem",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                java.nio.file.Files.writeString(databaseCaFile,certificate);
                databaseCaFile.toFile().deleteOnExit();
            }
            var properties=new java.util.HashMap<>(database.getProperties());
            properties.put("sslmode","verify-full");
            properties.put("sslrootcert",databaseCaFile.toString());
            database.setProperties(properties);
        } catch(java.io.IOException e) {throw new IllegalStateException("Unable to prepare database TLS",e);}
    }

    @Valid @NotNull @JsonProperty public KafkaSettings kafka = new KafkaSettings();
    @JsonProperty public boolean production = false;
    @Min(1) @Max(10000000) @JsonProperty public int maxQueuedEvents = 100000;
    @JsonProperty public boolean workerEnabled = true;

    @Min(1)
    @Max(128)
    @JsonProperty
    public int workerThreads = 8;

    @Min(1)
    @Max(20)
    @JsonProperty
    public int maxAttempts = 5;

    @Min(10)
    @Max(600)
    @JsonProperty
    public int leaseSeconds = 30;

    @Min(1)
    @Max(30)
    @JsonProperty
    public int requestTimeoutSeconds = 5;

    @Min(1)
    @Max(60000)
    @JsonProperty
    public long retryBaseMillis = 1000;

    @Min(1)
    @Max(3600000)
    @JsonProperty
    public long retryCapMillis = 60000;

    @Min(1)
    @JsonProperty
    public int requestsPerMinute = 600;

    public void validateSettings() {
        if (production && (!kafka.enabled || allowLocalWebhooks || !"SASL_SSL".equals(kafka.securityProtocol)
                || credentials.values().stream().anyMatch(v -> v.startsWith("local-")) || signingSecret.startsWith("local-"))) {
            throw new IllegalArgumentException("Production requires Kafka, TLS, non-demo secrets and public HTTPS webhooks");
        }
        if (leaseSeconds <= requestTimeoutSeconds + 2) {
            throw new IllegalArgumentException(
                    "Lease must exceed HTTP timeout by more than two seconds");
        }
        if (retryCapMillis < retryBaseMillis)
            throw new IllegalArgumentException("Retry cap must be >= base");
        if (credentials.entrySet().stream()
                        .anyMatch(e -> e.getKey().isBlank() || e.getValue().length() < 16)
                || credentials.values().stream().distinct().count() != credentials.size()) {
            throw new IllegalArgumentException(
                    "Tenant tokens must be unique and at least 16 characters");
        }
    }
}
