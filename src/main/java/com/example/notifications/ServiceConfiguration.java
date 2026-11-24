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
    @Valid @JsonProperty public DataSourceFactory readDatabase;
    @JsonProperty public boolean requireReplicaReads = false;
    @JsonProperty public Map<String,String> userIds = Map.of();
    @JsonProperty public Map<String,String> compartmentIds = Map.of();
    @Min(1) @Max(128) @JsonProperty public int subscriberConcurrency = 1;
    public Models.Identity identity(String tenant) {
        return new Models.Identity(tenant, userIds.getOrDefault(tenant, tenant), compartmentIds.getOrDefault(tenant, "default"));
    }
    @NotEmpty @JsonProperty public Map<String, String> credentials = Map.of();
    @NotEmpty @JsonProperty public List<String> allowedWebhookOrigins = List.of();
    @JsonProperty public boolean allowLocalWebhooks = false;
    @JsonProperty public String adminToken = "";

    @NotBlank
    @Size(min = 24)
    @JsonProperty
    public String signingSecret;

    @JsonProperty public String databaseCaCertificateEnvironment = "PG_CA_CERT";
    private java.nio.file.Path databaseCaFile;

    public void prepareDatabaseSecurity() {
        if(!production || singleHostDeployment) return;
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
            if (readDatabase != null) {
                var readProperties = new java.util.HashMap<>(readDatabase.getProperties());
                readProperties.put("sslmode", "verify-full"); readProperties.put("sslrootcert", databaseCaFile.toString());
                readDatabase.setProperties(readProperties);
            }
        } catch(java.io.IOException e) {throw new IllegalStateException("Unable to prepare database TLS",e);}
    }

    @Valid @NotNull @JsonProperty public KafkaSettings kafka = new KafkaSettings();
    @JsonProperty public boolean production = false;
    // Explicit budget deployment: plaintext is confined to one private Docker bridge.
    @JsonProperty public boolean singleHostDeployment = false;
    @Min(1) @Max(10000000) @JsonProperty public int maxQueuedEvents = 100000;
    @JsonProperty public boolean workerEnabled = true;

    @Min(1)
    @Max(128)
    @JsonProperty
    public int workerThreads = 8;

    @Min(1)
    @Max(20)
    @JsonProperty
    public int maxAttempts = 6;

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
        if (requireReplicaReads && readDatabase == null)
            throw new IllegalArgumentException("Replica-only reads require a separate standby pool");
        for (var tenant : credentials.keySet()) {
            var identity = identity(tenant);
            if (identity.userId().isBlank() || identity.userId().length() > 128
                    || identity.compartmentId().isBlank() || identity.compartmentId().length() > 128)
                throw new IllegalArgumentException("Authenticated user and compartment IDs must contain 1..128 characters");
        }
        if (production && readDatabase != null && (database.getMaxSize() > 16 || readDatabase.getMaxSize() > 16))
            throw new IllegalArgumentException("Application database pools exceed the reviewed per-replica budget");
        if (readDatabase != null && (!"secondary".equals(readDatabase.getProperties().get("targetServerType"))
                || !readDatabase.getValidationQuery().orElse("").contains("pg_is_in_recovery")))
            throw new IllegalArgumentException("Read pool must require and validate a PostgreSQL replica");
        if ((production || !adminToken.isEmpty()) && (adminToken.length() < 32
                || credentials.containsValue(adminToken) || production && adminToken.startsWith("local-"))) {
            throw new IllegalArgumentException("Use an independent administrator token of at least 32 characters");
        }
        if (production && (!kafka.enabled || allowLocalWebhooks
                || credentials.values().stream().anyMatch(v -> v.startsWith("local-"))
                || signingSecret.startsWith("local-"))) {
            throw new IllegalArgumentException("Production requires Kafka, non-demo secrets and public HTTPS webhooks");
        }
        if (production && singleHostDeployment) {
            if (!"kafka:9092".equals(kafka.bootstrapServers)
                    || !"PLAINTEXT".equals(kafka.securityProtocol)
                    || !"jdbc:postgresql://db:5432/notifications".equals(database.getUrl())
                    || !"disable".equals(database.getProperties().get("sslmode"))) {
                throw new IllegalArgumentException("Single-host mode requires the dedicated db and kafka Docker services");
            }
        } else if (production && !"SASL_SSL".equals(kafka.securityProtocol) && !"SSL".equals(kafka.securityProtocol)) {
            throw new IllegalArgumentException("Multi-host production Kafka requires authenticated TLS");
        }
        if (leaseSeconds <= requestTimeoutSeconds + 2) {
            throw new IllegalArgumentException(
                    "Lease must exceed HTTP timeout by more than two seconds");
        }
        if (retryCapMillis < retryBaseMillis)
            throw new IllegalArgumentException("Retry cap must be >= base");
        if (credentials.entrySet().stream()
                        .anyMatch(e -> e.getKey().isBlank() || e.getKey().length() > 100 || e.getValue().length() < 16)
                || credentials.values().stream().distinct().count() != credentials.size()) {
            throw new IllegalArgumentException(
                    "Tenant tokens must be unique and at least 16 characters");
        }
    }
}
