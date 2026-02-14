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

    private String keySerializer = "org.apache.kafka.common.serialization.ByteArraySerializer";

    private String valueSerializer = "org.apache.kafka.common.serialization.ByteArraySerializer";

    private String keyDeserializer = "org.apache.kafka.common.serialization.ByteArrayDeserializer";

    private String valueDeserializer = "org.apache.kafka.common.serialization.ByteArrayDeserializer";

    private String autoOffsetReset = "earliest";

    private boolean enableAutoCommit = true;

    private int autoCommitIntervalMs = 5000;

    private int sessionTimeoutMs = 30000;

    private int maxPollRecords = 500;

    private int maxPollIntervalMs = 300000;

    private int receiveBufferBytes = 65536;

    private int sendBufferBytes = 131072;

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
        return props;
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
                '}';
    }
}
