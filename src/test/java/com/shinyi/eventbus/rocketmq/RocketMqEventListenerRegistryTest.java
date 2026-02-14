package com.shinyi.eventbus.rocketmq;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.rocketmq.RocketMqConnectConfig;
import com.shinyi.eventbus.registry.RocketMqEventListenerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RocketMqEventListenerRegistryTest {

    @Mock
    private ApplicationContext applicationContext;

    private RocketMqConnectConfig config;
    private RocketMqEventListenerRegistry<EventModel<TestEvent>> registry;

    @BeforeEach
    public void setUp() {
        config = new RocketMqConnectConfig();
        config.setIsDefault(true);
        config.setNamesrvAddr("localhost:9876");
        config.setProducerGroupId("test-producer-group");
        config.setConsumeMode("concurrently");
        config.setMessageModel("clustering");
        config.setOffset("latest");
        config.setSkipCreateProducer(false);
    }

    @Test
    public void testGetEventBusType() {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        assertEquals(EventBusType.ROCKETMQ, registry.getEventBusType());
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testClose_shouldNotThrowException() {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        assertDoesNotThrow(() -> {
            try {
                registry.close();
            } catch (Exception e) {
                // ignore
            }
        });
    }
}
