package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
    private KafkaProducer<String, byte[]> kafkaProducer;

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
        assertEquals(StringSerializer.class.getName(), props.get("key.serializer"));
        assertEquals(ByteArraySerializer.class.getName(), props.get("value.serializer"));
    }

    @Test
    public void testDefaultSerializerValues_shouldBeByteArray() {
        KafkaConnectConfig defaultConfig = new KafkaConnectConfig();

        // Key uses StringSerializer because KafkaMqEventListenerRegistry uses String keys
        assertEquals("org.apache.kafka.common.serialization.StringSerializer", defaultConfig.getKeySerializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArraySerializer", defaultConfig.getValueSerializer());
        assertEquals("org.apache.kafka.common.serialization.StringDeserializer", defaultConfig.getKeyDeserializer());
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", defaultConfig.getValueDeserializer());
    }

    @Test
    public void testProducerCreation_withValidConfig() {
        Properties props = config.toProducerProperties();
        // Producer uses String key and byte[] value
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            assertNotNull(producer);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSendMessage_shouldCallProducerSend() throws Exception {
        when(kafkaProducer.send(any(ProducerRecord.class))).thenReturn(mock(Future.class));

        String key = "test-key";
        byte[] value = "test-value".getBytes();
        ProducerRecord<String, byte[]> record = new ProducerRecord<>("test-topic", key, value);

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

    // ==================== SASL/Kerberos Security Tests ====================

    @Test
    public void testSaslPlainText_shouldNotIncludeSecurityProps() {
        config.setSecurityProtocol("PLAINTEXT");
        config.setUsername("testuser");
        config.setPassword("testpass");

        Properties props = config.toProducerProperties();

        assertNull(props.get("security.protocol"));
        assertNull(props.get("sasl.mechanism"));
        assertNull(props.get("sasl.jaas.config"));
    }

    @Test
    public void testSaslPlainConfig_shouldIncludeCorrectProperties() {
        config.setSecurityProtocol("SASL_PLAINTEXT");
        config.setSaslMechanism("PLAIN");
        config.setUsername("testuser");
        config.setPassword("testpass");

        Properties props = config.toProducerProperties();

        assertEquals("SASL_PLAINTEXT", props.get("security.protocol"));
        assertEquals("PLAIN", props.get("sasl.mechanism"));
        assertNotNull(props.get("sasl.jaas.config"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("PlainLoginModule"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("username=\"testuser\""));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("password=\"testpass\""));
    }

    @Test
    public void testScramSha256Config_shouldIncludeCorrectProperties() {
        config.setSecurityProtocol("SASL_SSL");
        config.setSaslMechanism("SCRAM-SHA-256");
        config.setUsername("testuser");
        config.setPassword("testpass");

        Properties props = config.toProducerProperties();

        assertEquals("SASL_SSL", props.get("security.protocol"));
        assertEquals("SCRAM-SHA-256", props.get("sasl.mechanism"));
        assertNotNull(props.get("sasl.jaas.config"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("ScramLoginModule"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("username=\"testuser\""));
    }

    @Test
    public void testScramSha512Config_shouldIncludeCorrectProperties() {
        config.setSecurityProtocol("SASL_SSL");
        config.setSaslMechanism("SCRAM-SHA-512");
        config.setUsername("admin");
        config.setPassword("admin123");

        Properties props = config.toProducerProperties();

        assertEquals("SCRAM-SHA-512", props.get("sasl.mechanism"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("SCRAM-SHA-512") ||
                   ((String) props.get("sasl.jaas.config")).contains("scram"));
    }

    @Test
    public void testKerberosConfig_shouldIncludeCorrectJaasConfig() {
        config.setSecurityProtocol("SASL_PLAINTEXT");
        config.setSaslMechanism("GSSAPI");
        config.setKerberosServiceName("kafka");
        config.setKerberosPrincipal("kafka/kafka.example.com@EXAMPLE.COM");
        config.setKerberosKeytab("/etc/kafka/kafka.keytab");

        Properties props = config.toProducerProperties();

        assertEquals("SASL_PLAINTEXT", props.get("security.protocol"));
        assertEquals("GSSAPI", props.get("sasl.mechanism"));
        assertEquals("kafka", props.get("sasl.kerberos.service.name"));

        String jaasConfig = (String) props.get("sasl.jaas.config");
        assertNotNull(jaasConfig);
        assertTrue(jaasConfig.contains("Krb5LoginModule"));
        assertTrue(jaasConfig.contains("useKeyTab=true"));
        assertTrue(jaasConfig.contains("serviceName=\"kafka\""));
        assertTrue(jaasConfig.contains("principal=\"kafka/kafka.example.com@EXAMPLE.COM\""));
        assertTrue(jaasConfig.contains("keyTab=\"/etc/kafka/kafka.keytab\""));
    }

    @Test
    public void testKerberosWithKrb5Location_shouldSetSystemProperty() {
        config.setSaslMechanism("GSSAPI");
        config.setKerberosServiceName("kafka");
        config.setKerberosPrincipal("kafka/kafka.example.com@EXAMPLE.COM");
        config.setKerberosKeytab("/etc/kafka/kafka.keytab");
        config.setKerberosKrb5Location("/etc/kafka/krb5.conf");

        config.configureKerberosSystemProperties();

        assertEquals("/etc/kafka/krb5.conf", System.getProperty("java.security.krb5.conf"));
    }

    @Test
    public void testConsumerProperties_shouldAlsoSupportSecurity() {
        config.setGroupId("test-consumer-group");
        config.setSecurityProtocol("SASL_SSL");
        config.setSaslMechanism("SCRAM-SHA-512");
        config.setUsername("consumer");
        config.setPassword("consumerpass");

        Properties props = config.toConsumerProperties();

        assertEquals("SASL_SSL", props.get("security.protocol"));
        assertEquals("SCRAM-SHA-512", props.get("sasl.mechanism"));
        assertNotNull(props.get("sasl.jaas.config"));
    }

    @Test
    public void testSecurityProtocolDefaultsToPlainText() {
        KafkaConnectConfig defaultConfig = new KafkaConnectConfig();

        assertEquals("PLAINTEXT", defaultConfig.getSecurityProtocol());
        assertEquals("PLAIN", defaultConfig.getSaslMechanism());
    }
}
