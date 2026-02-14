package com.shinyi.eventbus.rocketmq;

import com.shinyi.eventbus.config.rocketmq.RocketMqConnectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RocketMqConnectConfigTest {

    private RocketMqConnectConfig config;

    @BeforeEach
    public void setUp() {
        config = new RocketMqConnectConfig();
    }

    @Test
    public void testDefaultValues() {
        assertEquals(Boolean.FALSE, config.getIsDefault());
        assertEquals("aliyun", config.getBackendType());
        assertEquals("latest", config.getOffset());
        assertEquals("concurrently", config.getConsumeMode());
        assertEquals("clustering", config.getMessageModel());
        assertEquals(3, config.getRetryTimesWhenSendFailed());
        assertEquals(3000, config.getSendMsgTimeout());
        assertEquals(10000, config.getConsumerTimeoutMillis());
        assertEquals(16, config.getMaxReconsumeTimes());
        assertEquals(32, config.getPullBatchSize());
        assertEquals(1, config.getMaxBatchConsumeWaitTime());
    }

    @Test
    public void testSettersAndGetters() {
        config.setIsDefault(true);
        assertEquals(Boolean.TRUE, config.getIsDefault());

        config.setBackendType("apache");
        assertEquals("apache", config.getBackendType());

        config.setProducerAppName("test-app");
        assertEquals("test-app", config.getProducerAppName());

        config.setProducerTopic("test-topic");
        assertEquals("test-topic", config.getProducerTopic());

        config.setAccessKey("test-access-key");
        assertEquals("test-access-key", config.getAccessKey());

        config.setSecretKey("test-secret-key");
        assertEquals("test-secret-key", config.getSecretKey());

        config.setNamesrvAddr("localhost:9876");
        assertEquals("localhost:9876", config.getNamesrvAddr());

        config.setProducerGroupId("test-producer-group");
        assertEquals("test-producer-group", config.getProducerGroupId());

        config.setSkipCreateProducer(true);
        assertTrue(config.isSkipCreateProducer());

        config.setOffset("earliest");
        assertEquals("earliest", config.getOffset());

        config.setConsumeTimestamp("20240101000000");
        assertEquals("20240101000000", config.getConsumeTimestamp());

        config.setConsumeMode("orderly");
        assertEquals("orderly", config.getConsumeMode());

        config.setMessageModel("broadcasting");
        assertEquals("broadcasting", config.getMessageModel());

        config.setRetryTimesWhenSendFailed(5);
        assertEquals(5, config.getRetryTimesWhenSendFailed());

        config.setSendMsgTimeout(5000);
        assertEquals(5000, config.getSendMsgTimeout());

        config.setConsumerTimeoutMillis(20000);
        assertEquals(20000, config.getConsumerTimeoutMillis());

        config.setMaxReconsumeTimes(10);
        assertEquals(10, config.getMaxReconsumeTimes());

        config.setPullBatchSize(64);
        assertEquals(64, config.getPullBatchSize());

        config.setMaxBatchConsumeWaitTime(5);
        assertEquals(5, config.getMaxBatchConsumeWaitTime());
    }

    @Test
    public void testConsumeModes() {
        config.setConsumeMode("concurrently");
        assertEquals("concurrently", config.getConsumeMode());

        config.setConsumeMode("orderly");
        assertEquals("orderly", config.getConsumeMode());
    }

    @Test
    public void testMessageModels() {
        config.setMessageModel("clustering");
        assertEquals("clustering", config.getMessageModel());

        config.setMessageModel("broadcasting");
        assertEquals("broadcasting", config.getMessageModel());
    }

    @Test
    public void testOffsetValues() {
        config.setOffset("earliest");
        assertEquals("earliest", config.getOffset());

        config.setOffset("latest");
        assertEquals("latest", config.getOffset());

        config.setOffset("timestamp");
        assertEquals("timestamp", config.getOffset());

        config.setOffset("none");
        assertEquals("none", config.getOffset());
    }

    @Test
    public void testToString() {
        config.setIsDefault(true);
        config.setBackendType("apache");
        config.setNamesrvAddr("localhost:9876");
        config.setProducerGroupId("test-group");

        String configString = config.toString();

        assertTrue(configString.contains("isDefault=true"));
        assertTrue(configString.contains("backendType='apache'"));
        assertTrue(configString.contains("namesrvAddr='localhost:9876'"));
        assertTrue(configString.contains("producerGroupId='test-group'"));
    }
}
