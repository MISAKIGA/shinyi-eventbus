package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class KafkaConsumerTest {

    private KafkaConnectConfig config;

    @BeforeEach
    public void setUp() {
        config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setTopic("test-topic");
        config.setGroupId("test-group");
        config.setClientId("test-client");
    }

    @Test
    public void testToConsumerProperties_shouldReturnCorrectProperties() {
        Properties props = config.toConsumerProperties();

        assertEquals("localhost:9092", props.get("bootstrap.servers"));
        assertEquals("test-group", props.get("group.id"));
        assertEquals("test-client", props.get("client.id"));
        assertEquals(ByteArrayDeserializer.class.getName(), props.get("key.deserializer"));
        assertEquals(ByteArrayDeserializer.class.getName(), props.get("value.deserializer"));
    }

    @Test
    public void testDefaultDeserializerValues_shouldBeByteArray() {
        KafkaConnectConfig defaultConfig = new KafkaConnectConfig();

        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", defaultConfig.getKeyDeserializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", defaultConfig.getValueDeserializer());
    }

    @Test
    public void testConsumerCreation_withValidConfig() {
        Properties props = config.toConsumerProperties();
        props.setProperty("auto.offset.reset", "earliest");
        
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            assertNotNull(consumer);
        }
    }

    @Test
    public void testConsumerConfig_withAllFields() {
        config.setAutoOffsetReset("latest");
        config.setEnableAutoCommit(false);
        config.setAutoCommitIntervalMs(10000);
        config.setSessionTimeoutMs(45000);
        config.setMaxPollRecords(1000);
        config.setMaxPollIntervalMs(600000);
        config.setReceiveBufferBytes(131072);
        config.setSendBufferBytes(262144);

        Properties props = config.toConsumerProperties();

        assertEquals("latest", props.get("auto.offset.reset"));
        assertEquals(false, props.get("enable.auto.commit"));
        assertEquals(10000, props.get("auto.commit.interval.ms"));
        assertEquals(45000, props.get("session.timeout.ms"));
        assertEquals(1000, props.get("max.poll.records"));
        assertEquals(600000, props.get("max.poll.interval.ms"));
        assertEquals(131072, props.get("receive.buffer.bytes"));
        assertEquals(262144, props.get("send.buffer.bytes"));
    }

    @Test
    public void testConsumerConfig_toString() {
        config.setBootstrapServers("localhost:9092");
        config.setTopic("test-topic");
        config.setGroupId("test-group");

        String configString = config.toString();

        assertTrue(configString.contains("bootstrapServers='localhost:9092'"));
        assertTrue(configString.contains("topic='test-topic'"));
        assertTrue(configString.contains("groupId='test-group'"));
    }

    @Test
    public void testConsumerConfig_settersAndGetters() {
        KafkaConnectConfig config = new KafkaConnectConfig();

        config.setBootstrapServers("localhost:9092");
        assertEquals("localhost:9092", config.getBootstrapServers());

        config.setTopic("test-topic");
        assertEquals("test-topic", config.getTopic());

        config.setGroupId("test-group");
        assertEquals("test-group", config.getGroupId());

        config.setClientId("client-1");
        assertEquals("client-1", config.getClientId());
    }

    @Test
    public void testConsumerProperties_withoutClientId() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setGroupId("test-group");
        
        Properties props = config.toConsumerProperties();

        assertFalse(props.containsKey("client.id"));
    }

    @Test
    public void testConsumerProperties_withEmptyClientId() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setGroupId("test-group");
        config.setClientId("");
        
        Properties props = config.toConsumerProperties();

        assertFalse(props.containsKey("client.id"));
    }
}
