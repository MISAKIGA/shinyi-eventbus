package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Properties;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class KafkaProducerTest {

    @Mock
    private KafkaProducer<byte[], byte[]> kafkaProducer;

    private KafkaConnectConfig config;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setTopic("test-topic");
        config.setClientId("test-client");
        config.setAcks("1");
        config.setRetries(3);
    }

    @Test
    public void testToProducerProperties_shouldReturnCorrectProperties() {
        Properties props = config.toProducerProperties();

        assertEquals("localhost:9092", props.get("bootstrap.servers"));
        assertEquals("1", props.get("acks"));
        assertEquals(3, props.get("retries"));
        assertEquals(ByteArraySerializer.class.getName(), props.get("key.serializer"));
        assertEquals(ByteArraySerializer.class.getName(), props.get("value.serializer"));
    }

    @Test
    public void testDefaultSerializerValues_shouldBeByteArray() {
        KafkaConnectConfig defaultConfig = new KafkaConnectConfig();

        assertEquals("org.apache.kafka.common.serialization.ByteArraySerializer", defaultConfig.getKeySerializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArraySerializer", defaultConfig.getValueSerializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", defaultConfig.getKeyDeserializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", defaultConfig.getValueDeserializer());
    }

    @Test
    public void testProducerCreation_withValidConfig() {
        Properties props = config.toProducerProperties();
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            assertNotNull(producer);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSendMessage_shouldCallProducerSend() throws Exception {
        when(kafkaProducer.send(any(ProducerRecord.class))).thenReturn(mock(Future.class));

        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("test-topic", key, value);

        kafkaProducer.send(record);

        verify(kafkaProducer, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    public void testProducerConfig_withAllFields() {
        config.setAcks("all");
        config.setBatchSize(32768);
        config.setLingerMs(10);
        config.setBufferMemory(67108864);
        config.setMaxInFlightRequestsPerConnection(10);

        Properties props = config.toProducerProperties();

        assertEquals("all", props.get("acks"));
        assertEquals(32768, props.get("batch.size"));
        assertEquals(10, props.get("linger.ms"));
        assertEquals(67108864, props.get("buffer.memory"));
        assertEquals(10, props.get("max.in.flight.requests.per.connection"));
    }

    @Test
    public void testProducerConfig_toString() {
        config.setBootstrapServers("localhost:9092");
        config.setTopic("test-topic");
        config.setGroupId("test-group");

        String configString = config.toString();

        assertTrue(configString.contains("bootstrapServers='localhost:9092'"));
        assertTrue(configString.contains("topic='test-topic'"));
        assertTrue(configString.contains("groupId='test-group'"));
    }

    @Test
    public void testProducerConfig_settersAndGetters() {
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
}
