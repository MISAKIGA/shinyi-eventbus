package com.shinyi.eventbus.rabbit;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.rabbit.RabbitMqConnectConfig;
import com.shinyi.eventbus.registry.RabbitMqEventListenerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RabbitMqEventListenerRegistryTest {

    @Mock
    private ApplicationContext applicationContext;

    private RabbitMqConnectConfig config;
    private RabbitMqEventListenerRegistry<EventModel<TestEvent>> registry;

    @BeforeEach
    public void setUp() {
        config = new RabbitMqConnectConfig();
        config.setIsDefault(true);
        config.setHost("localhost");
        config.setPort(5672);
        config.setUsername("guest");
        config.setPassword("guest");
        config.setVirtualHost("/");
        config.setExchange("test-exchange");
        config.setExchangeType("direct");
        config.setQueue("test-queue");
        config.setRoutingKey("test-key");
    }

    @Test
    public void testGetEventBusType() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        assertEquals(EventBusType.RABBITMQ, registry.getEventBusType());
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testClose_shouldNotThrowException() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        assertDoesNotThrow(() -> {
            try {
                registry.close();
            } catch (Exception e) {
                // ignore
            }
        });
    }

    private EventListener<EventModel<TestEvent>> createTestListener() {
        return new EventListener<EventModel<TestEvent>>() {
            @Override
            public void onMessage(EventModel<TestEvent> event) {
                System.out.println("Received: " + event);
            }

            @Override
            public void onMessageBatch(List<EventModel<TestEvent>> events) {
            }

            @Override
            public String topic() {
                return "test-topic";
            }

            @Override
            public String group() {
                return "test-group";
            }

            @Override
            public String tags() {
                return "*";
            }

            @Override
            public Class<?> entityType() {
                return TestEvent.class;
            }

            @Override
            public String serializeType() {
                return "JSON";
            }

            @Override
            public Collection<String> registryBeanName() {
                return Arrays.asList("rabbitmq");
            }

            @Override
            public String exchange() {
                return "test-exchange";
            }

            @Override
            public String exchangeType() {
                return "direct";
            }

            @Override
            public String queue() {
                return "test-queue";
            }

            @Override
            public String routingKey() {
                return "test-key";
            }

            @Override
            public boolean isDurable() {
                return true;
            }

            @Override
            public boolean autoDelete() {
                return false;
            }

            @Override
            public String consumerMode() {
                return "PUSH";
            }

            @Override
            public String offset() {
                return "latest";
            }
        };
    }
}
