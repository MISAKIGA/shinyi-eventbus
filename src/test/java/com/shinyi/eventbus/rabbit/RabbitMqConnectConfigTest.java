package com.shinyi.eventbus.rabbit;

import com.shinyi.eventbus.config.rabbit.RabbitMqConnectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RabbitMqConnectConfigTest {

    private RabbitMqConnectConfig config;

    @BeforeEach
    public void setUp() {
        config = new RabbitMqConnectConfig();
    }

    @Test
    public void testDefaultValues() {
        assertEquals(Boolean.FALSE, config.getIsDefault());
        assertEquals("", config.getBackendType());
        assertEquals("", config.getExchange());
        assertEquals("direct", config.getExchangeType());
        assertEquals("/", config.getVirtualHost());
        assertEquals("", config.getRoutingKey());
        assertTrue(config.isDurable());
        assertFalse(config.isAutoDelete());
        assertFalse(config.isSkipCreateProducer());
        assertEquals(3000, config.getSendMsgTimeout());
        assertEquals(10000, config.getConsumerTimeoutMillis());
    }

    @Test
    public void testSettersAndGetters() {
        config.setIsDefault(true);
        assertEquals(Boolean.TRUE, config.getIsDefault());

        config.setBackendType("rabbitmq");
        assertEquals("rabbitmq", config.getBackendType());

        config.setUsername("admin");
        assertEquals("admin", config.getUsername());

        config.setPassword("password");
        assertEquals("password", config.getPassword());

        config.setHost("localhost");
        assertEquals("localhost", config.getHost());

        config.setPort(5672);
        assertEquals(5672, config.getPort());

        config.setExchange("test-exchange");
        assertEquals("test-exchange", config.getExchange());

        config.setExchangeType("fanout");
        assertEquals("fanout", config.getExchangeType());

        config.setVirtualHost("/test");
        assertEquals("/test", config.getVirtualHost());

        config.setDurable(false);
        assertFalse(config.isDurable());

        config.setAutoDelete(true);
        assertTrue(config.isAutoDelete());

        config.setQueue("test-queue");
        assertEquals("test-queue", config.getQueue());

        config.setRoutingKey("test-key");
        assertEquals("test-key", config.getRoutingKey());

        config.setSkipCreateProducer(true);
        assertTrue(config.isSkipCreateProducer());

        config.setSendMsgTimeout(5000);
        assertEquals(5000, config.getSendMsgTimeout());

        config.setConsumerTimeoutMillis(20000);
        assertEquals(20000, config.getConsumerTimeoutMillis());
    }

    @Test
    public void testExchangeTypes() {
        config.setExchangeType("direct");
        assertEquals("direct", config.getExchangeType());

        config.setExchangeType("fanout");
        assertEquals("fanout", config.getExchangeType());

        config.setExchangeType("topic");
        assertEquals("topic", config.getExchangeType());
    }

    @Test
    public void testToString() {
        config.setHost("localhost");
        config.setPort(5672);
        config.setUsername("admin");
        config.setPassword("password");
        config.setExchange("test-exchange");
        config.setQueue("test-queue");

        String configString = config.toString();

        assertTrue(configString.contains("localhost"));
        assertTrue(configString.contains("5672"));
        assertTrue(configString.contains("test-exchange"));
        assertTrue(configString.contains("test-queue"));
    }
}
