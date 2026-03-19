package com.shinyi.eventbus.config.kafka;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@Slf4j
@NoArgsConstructor
@Data
public class KafkaConnectConfig {

    private Boolean isDefault = Boolean.FALSE;

    private String bootstrapServers;

    private String topic;

    private String groupId;

    private String clientId;

    private String acks = "1";

    private int retries = 3;

    private int batchSize = 16384;

    private int lingerMs = 1;

    private int bufferMemory = 33554432;

    private int maxInFlightRequestsPerConnection = 5;

    private String keySerializer = "org.apache.kafka.common.serialization.StringSerializer";

    private String valueSerializer = "org.apache.kafka.common.serialization.ByteArraySerializer";

    private String keyDeserializer = "org.apache.kafka.common.serialization.StringDeserializer";

    private String valueDeserializer = "org.apache.kafka.common.serialization.ByteArrayDeserializer";

    private String autoOffsetReset = "earliest";

    private boolean enableAutoCommit = true;

    private int autoCommitIntervalMs = 5000;

    private int sessionTimeoutMs = 30000;

    private int maxPollRecords = 500;

    private int maxPollIntervalMs = 300000;

    private int receiveBufferBytes = 65536;

    private int sendBufferBytes = 131072;

    // ==================== Security (SASL/Kerberos) ====================

    /**
     * Security protocol: PLAINTEXT, SASL_PLAINTEXT, SASL_SSL
     */
    private String securityProtocol = "PLAINTEXT";

    /**
     * SASL mechanism: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI
     */
    private String saslMechanism = "PLAIN";

    /**
     * SASL username (for PLAIN, SCRAM-SHA-256, SCRAM-SHA-512)
     */
    private String username;

    /**
     * SASL password (for PLAIN, SCRAM-SHA-256, SCRAM-SHA-512)
     */
    private String password;

    // ==================== Kerberos (GSSAPI) ====================

    /**
     * Kerberos service name (default: kafka)
     */
    private String kerberosServiceName = "kafka";

    /**
     * Kerberos principal (e.g., kafka/kafka.example.com@EXAMPLE.COM)
     */
    private String kerberosPrincipal;

    /**
     * Kerberos keytab file path (e.g., /etc/kafka/kafka.keytab)
     */
    private String kerberosKeytab;

    /**
     * Kerberos krb5.conf file path (e.g., /etc/kafka/krb5.conf)
     */
    private String kerberosKrb5Location;

    // ==================== Performance Optimization (P0.2) ====================

    /**
     * Compression type for producer: none, gzip, snappy, lz4, zstd
     * Default: snappy (optimized for throughput)
     */
    private String compressionType = "snappy";

    /**
     * Consumer fetch.min.bytes setting - minimum amount of data to fetch per request
     * Default: 1024 (1KB) - optimized for throughput
     */
    private int fetchMinBytes = 1024;

    /**
     * Consumer fetch.max.wait.ms setting - maximum wait time for fetch request
     * Default: 1000 (1s) - optimized for throughput
     */
    private int fetchMaxWaitMs = 1000;

    /**
     * Consumer max.partition.fetch.bytes setting - maximum data per partition per fetch
     * Default: 1048576 (1MB) - optimized for throughput
     */
    private int maxPartitionFetchBytes = 1048576;

    // ==================== Exactly-Once Semantics (P0.3) ====================

    /**
     * Enable idempotence for exactly-once producer semantics
     * When enabled: acks=all, retries=MAX, max.in.flight.requests.per.connection=5
     */
    private boolean enableIdempotence = false;

    /**
     * Enable manual offset commit for exactly-once consumer semantics
     * When enabled: enable.auto.commit=false, manual commitSync after batch processing
     */
    private boolean enableManualCommit = false;

    /**
     * Batch size for manual commit (number of messages to process before commit)
     * Only used when enableManualCommit=true
     */
    private int commitBatchSize = 100;

    public Properties toProducerProperties() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("acks", acks);
        props.put("retries", retries);
        props.put("batch.size", batchSize);
        props.put("linger.ms", lingerMs);
        props.put("buffer.memory", bufferMemory);
        props.put("max.in.flight.requests.per.connection", maxInFlightRequestsPerConnection);
        props.put("key.serializer", keySerializer);
        props.put("value.serializer", valueSerializer);

        // Apply compression type (P0.2 performance optimization)
        if (compressionType != null && !compressionType.isEmpty()) {
            props.put("compression.type", compressionType);
        }

        // Apply idempotence settings for EOS (P0.3)
        if (enableIdempotence) {
            props.put("enable.idempotence", true);
            props.put("acks", "all");
            props.put("retries", Integer.MAX_VALUE);
            props.put("max.in.flight.requests.per.connection", 5);
        }

        // Apply security/SASL configuration
        applySecurityProperties(props);

        return props;
    }

    public Properties toConsumerProperties() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        if (clientId != null && !clientId.isEmpty()) {
            props.put("client.id", clientId);
        }
        props.put("key.deserializer", keyDeserializer);
        props.put("value.deserializer", valueDeserializer);
        props.put("auto.offset.reset", autoOffsetReset);
        props.put("enable.auto.commit", enableAutoCommit);
        props.put("auto.commit.interval.ms", autoCommitIntervalMs);
        props.put("session.timeout.ms", sessionTimeoutMs);
        props.put("max.poll.records", maxPollRecords);
        props.put("max.poll.interval.ms", maxPollIntervalMs);
        props.put("receive.buffer.bytes", receiveBufferBytes);
        props.put("send.buffer.bytes", sendBufferBytes);

        // Apply fetch optimization settings (P0.2)
        props.put("fetch.min.bytes", fetchMinBytes);
        props.put("fetch.max.wait.ms", fetchMaxWaitMs);
        props.put("max.partition.fetch.bytes", maxPartitionFetchBytes);

        // Apply manual commit settings for EOS (P0.3)
        if (enableManualCommit) {
            props.put("enable.auto.commit", false);
        }

        // Apply security/SASL configuration
        applySecurityProperties(props);

        return props;
    }

    /**
     * Apply SASL/Kerberos security properties to the given Properties object.
     * This method handles PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, and GSSAPI (Kerberos).
     */
    private void applySecurityProperties(Properties props) {
        // Only apply security settings if not using PLAINTEXT
        if ("PLAINTEXT".equals(securityProtocol)) {
            return;
        }

        props.put("security.protocol", securityProtocol);
        props.put("sasl.mechanism", saslMechanism);

        if ("GSSAPI".equals(saslMechanism)) {
            // Kerberos authentication
            applyKerberosJaasConfig(props);
        } else {
            // PLAIN or SCRAM authentication
            applyUsernamePasswordJaasConfig(props);
        }
    }

    /**
     * Apply Kerberos (GSSAPI) JAAS configuration.
     */
    private void applyKerberosJaasConfig(Properties props) {
        if (kerberosServiceName == null || kerberosPrincipal == null || kerberosKeytab == null) {
            log.warn("Kerberos authentication configured but missing required parameters: " +
                    "kerberosServiceName={}, kerberosPrincipal={}, kerberosKeytab={}",
                    kerberosServiceName, kerberosPrincipal, kerberosKeytab);
            return;
        }

        String jaasConfig = String.format(
                "com.sun.security.auth.module.Krb5LoginModule required " +
                "useKeyTab=true storeKey=true serviceName=\"%s\" principal=\"%s\" keyTab=\"%s\";",
                kerberosServiceName, kerberosPrincipal, kerberosKeytab);

        props.put("sasl.jaas.config", jaasConfig);

        // Set Kerberos-specific properties
        props.put("sasl.kerberos.service.name", kerberosServiceName);

        if (kerberosKrb5Location != null && !kerberosKrb5Location.isEmpty()) {
            props.put("sasl.kerberos.krb5.location", kerberosKrb5Location);
        }
    }

    /**
     * Apply username/password JAAS configuration for PLAIN and SCRAM mechanisms.
     */
    private void applyUsernamePasswordJaasConfig(Properties props) {
        if (username == null || password == null) {
            log.warn("SASL authentication configured but missing username or password");
            return;
        }

        String jaasConfig;
        if ("PLAIN".equals(saslMechanism)) {
            jaasConfig = String.format(
                    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                    "username=\"%s\" password=\"%s\";",
                    username, password);
        } else if (saslMechanism.startsWith("SCRAM-")) {
            // SCRAM-SHA-256 or SCRAM-SHA-512
            jaasConfig = String.format(
                    "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                    "username=\"%s\" password=\"%s\";",
                    username, password);
        } else {
            log.warn("Unsupported SASL mechanism: {}", saslMechanism);
            return;
        }

        props.put("sasl.jaas.config", jaasConfig);
    }

    @Override
    public String toString() {
        return "KafkaConnectConfig{" +
                "isDefault=" + isDefault +
                ", bootstrapServers='" + bootstrapServers + '\'' +
                ", topic='" + topic + '\'' +
                ", groupId='" + groupId + '\'' +
                ", clientId='" + clientId + '\'' +
                ", acks='" + acks + '\'' +
                ", retries=" + retries +
                ", batchSize=" + batchSize +
                ", lingerMs=" + lingerMs +
                ", bufferMemory=" + bufferMemory +
                ", maxInFlightRequestsPerConnection=" + maxInFlightRequestsPerConnection +
                ", keySerializer='" + keySerializer + '\'' +
                ", valueSerializer='" + valueSerializer + '\'' +
                ", keyDeserializer='" + keyDeserializer + '\'' +
                ", valueDeserializer='" + valueDeserializer + '\'' +
                ", autoOffsetReset='" + autoOffsetReset + '\'' +
                ", enableAutoCommit=" + enableAutoCommit +
                ", autoCommitIntervalMs=" + autoCommitIntervalMs +
                ", sessionTimeoutMs=" + sessionTimeoutMs +
                ", maxPollRecords=" + maxPollRecords +
                ", maxPollIntervalMs=" + maxPollIntervalMs +
                ", receiveBufferBytes=" + receiveBufferBytes +
                ", sendBufferBytes=" + sendBufferBytes +
                ", securityProtocol='" + securityProtocol + '\'' +
                ", saslMechanism='" + saslMechanism + '\'' +
                ", kerberosServiceName='" + kerberosServiceName + '\'' +
                // Performance optimization (P0.2)
                ", compressionType='" + compressionType + '\'' +
                ", fetchMinBytes=" + fetchMinBytes +
                ", fetchMaxWaitMs=" + fetchMaxWaitMs +
                ", maxPartitionFetchBytes=" + maxPartitionFetchBytes +
                // EOS settings (P0.3)
                ", enableIdempotence=" + enableIdempotence +
                ", enableManualCommit=" + enableManualCommit +
                ", commitBatchSize=" + commitBatchSize +
                '}';
    }

    /**
     * Configure Kerberos system properties for JVM-wide Kerberos support.
     * This must be called before creating Kafka clients when using GSSAPI/Kerberos.
     *
     * Reference: kafka-demo's KafkaKerberosTest.java
     */
    public void configureKerberosSystemProperties() {
        if (!"GSSAPI".equals(saslMechanism)) {
            return;
        }

        // Set krb5.conf location
        if (kerberosKrb5Location != null && !kerberosKrb5Location.isEmpty()) {
            System.setProperty("java.security.krb5.conf", kerberosKrb5Location);
            log.info("Set system property: java.security.krb5.conf={}", kerberosKrb5Location);
        }

        // Note: JAAS config can be provided via sasl.jaas.config property (preferred)
        // or via java.security.auth.login.config system property
        // We use the sasl.jaas.config property approach which is more portable
    }
}
