package com.example.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.Properties;

public class KafkaSettings {
    @JsonProperty public boolean enabled = true;
    @JsonProperty public boolean publisherEnabled = true;
    @JsonProperty public boolean consumerEnabled = true;
    @NotBlank @JsonProperty public String bootstrapServers = "notification-kafka:9092";
    @NotBlank @JsonProperty public String topic = "notification-events-v1";
    @NotBlank @JsonProperty public String deadLetterTopic = "notification-deliveries-dlq-v1";
    @NotBlank @JsonProperty public String groupId = "notification-dispatch-v1";
    @Pattern(regexp="PLAINTEXT|SASL_SSL|SSL") @JsonProperty public String securityProtocol = "PLAINTEXT";
    @JsonProperty public String username = "";
    @JsonProperty public String password = "";
    @JsonProperty public String caCertificate = "";
    @JsonProperty public String caCertificateEnvironment = "KAFKA_CA_CERT";
    @JsonProperty public String clientCertificate = "";
    @JsonProperty public String clientPrivateKey = "";
    @JsonProperty public String clientCertificateEnvironment = "KAFKA_CLIENT_CERT";
    @JsonProperty public String clientPrivateKeyEnvironment = "KAFKA_CLIENT_KEY";
    @Min(1) @Max(8) @JsonProperty public int publisherThreads = 2;

    public Properties properties(String clientId) {
        var result = new Properties();
        result.put("bootstrap.servers",bootstrapServers);
        result.put("client.id",clientId);
        result.put("security.protocol",securityProtocol);
        result.put("default.api.timeout.ms","10000");
        result.put("request.timeout.ms","5000");
        // Never log resolved client properties: they contain credentials.
        if ("SSL".equals(securityProtocol)) {
            var certificate=caCertificate.isBlank()?System.getenv(caCertificateEnvironment):caCertificate;
            var client=clientCertificate.isBlank()?System.getenv(clientCertificateEnvironment):clientCertificate;
            var key=clientPrivateKey.isBlank()?System.getenv(clientPrivateKeyEnvironment):clientPrivateKey;
            if(certificate==null || certificate.isBlank() || client==null || client.isBlank() || key==null || key.isBlank())
                throw new IllegalArgumentException("Kafka mutual TLS requires CA, client certificate and private key");
            result.put("ssl.truststore.type","PEM");
            result.put("ssl.truststore.certificates",certificate);
            result.put("ssl.keystore.type","PEM");
            result.put("ssl.keystore.certificate.chain",client);
            result.put("ssl.keystore.key",key);
            result.put("ssl.endpoint.identification.algorithm","https");
        }
        if ("SASL_SSL".equals(securityProtocol)) {
            var certificate=caCertificate.isBlank()?System.getenv(caCertificateEnvironment):caCertificate;
            if(username.isBlank() || password.isBlank() || certificate==null || certificate.isBlank())
                throw new IllegalArgumentException("Kafka TLS/SASL credentials and CA are required");
            result.put("sasl.mechanism","SCRAM-SHA-256");
            result.put("sasl.jaas.config","org.apache.kafka.common.security.scram.ScramLoginModule required username=\""
                + escape(username) + "\" password=\"" + escape(password) + "\";");
            result.put("ssl.truststore.type","PEM");
            result.put("ssl.truststore.certificates",certificate);
            result.put("ssl.endpoint.identification.algorithm","https");
        }
        return result;
    }
    private static String escape(String value) { return value.replace("\\","\\\\").replace("\"","\\\""); }
}
