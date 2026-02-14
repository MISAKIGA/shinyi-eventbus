package com.shinyi.eventbus.rabbit;

import com.rabbitmq.client.*;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.rabbit.RabbitMqConnectConfig;
import com.shinyi.eventbus.registry.RabbitMqEventListenerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RabbitMqEventListenerRegistryTest {

    @Mock
    private Connection producerConnection;

    @Mock
    private Connection consumerConnection;

    @Mock
    private Channel producerChannel;

    @Mock
    private Channel producerAsyncChannel;

    @Mock
    private Channel consumerChannel;

    @Mock
    private ApplicationContext applicationContext;

    private RabbitMqConnectConfig config;
    private RabbitMqEventListenerRegistry<EventModel<TestEvent>> registry;

    @BeforeEach
    public void setUp() throws IOException, TimeoutException {
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

        when(producerConnection.createChannel()).thenReturn(producerChannel, producerAsyncChannel);
        when(consumerConnection.createChannel()).thenReturn(consumerChannel);
    }

    @Test
    public void testGetEventBusType() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        assertEquals(EventBusType.RABBITMQ, registry.getEventBusType());
    }

    @Test
    public void testInitRegistryEventListener_shouldRegisterListener() throws Exception {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        setRegistryConnection(registry);

        List<EventListener<EventModel<TestEvent>>> listeners = new ArrayList<>();
        EventListener<EventModel<TestEvent>> listener = createTestListener();
        listeners.add(listener);

        registry.initRegistryEventListener(listeners);

        verify(consumerChannel, atLeastOnce()).exchangeDeclare(anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        verify(consumerChannel, atLeastOnce()).queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), any());
        verify(consumerChannel, atLeastOnce()).queueBind(anyString(), anyString(), anyString());
        verify(consumerChannel, atLeastOnce()).basicConsume(anyString(), anyBoolean(), any(DefaultConsumer.class));
    }

    @Test
    public void testPublishSync_shouldPublishMessage() throws Exception {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        setRegistryConnection(registry);
        
        when(producerChannel.getNextPublishSeqNo()).thenReturn(1L);
        when(producerChannel.waitForConfirms()).thenReturn(true);

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");
        event.setFieldTest2("test-data2");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setTags("test-tag");

        registry.publish(eventModel);

        verify(producerChannel, times(1)).confirmSelect();
        verify(producerChannel, times(1)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
        verify(producerChannel, times(1)).waitForConfirms();
    }

    @Test
    public void testPublishAsync_shouldPublishMessage() throws Exception {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        setRegistryConnection(registry);

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setTags("test-tag");
        eventModel.setEnableAsync(true);

        EventCallback callback = mock(EventCallback.class);
        eventModel.setEventCallback(callback);

        registry.publish(eventModel);

        verify(producerAsyncChannel, atLeastOnce()).confirmSelect();
        verify(producerAsyncChannel, atLeastOnce()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    public void testPublishWithNullCallback_shouldNotThrowException() throws Exception {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        setRegistryConnection(registry);
        
        when(producerChannel.getNextPublishSeqNo()).thenReturn(1L);
        when(producerChannel.waitForConfirms()).thenReturn(true);

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEventCallback(null);

        assertDoesNotThrow(() -> registry.publish(eventModel));
    }

    @Test
    public void testClose_shouldCloseAllConnections() throws Exception {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        setRegistryConnection(registry);

        registry.close();

        verify(producerChannel, times(1)).close();
        verify(producerAsyncChannel, times(1)).close();
        verify(producerConnection, times(1)).close();
        verify(consumerChannel, times(1)).close();
        verify(consumerConnection, times(1)).close();
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testDeserialize_withJsonSerializeType() {
        registry = new RabbitMqEventListenerRegistry<>(applicationContext, "rabbitmq", config);
        
        TestEvent event = new TestEvent();
        event.setFieldTest("test");
        
        byte[] body = "{\"fieldTest\":\"test\"}".getBytes();
        
        EventListener<EventModel<TestEvent>> listener = createTestListener();
        
        EventModel<?> result = registry.deserialize(body, "consumerTag", listener);
        
        assertNotNull(result);
        assertEquals("test-topic", result.getTopic());
    }

    private void setRegistryConnection(RabbitMqEventListenerRegistry<EventModel<TestEvent>> registry) throws Exception {
        try {
            java.lang.reflect.Field producerConnectionField = RabbitMqEventListenerRegistry.class.getDeclaredField("producerConnection");
            producerConnectionField.setAccessible(true);
            producerConnectionField.set(registry, producerConnection);

            java.lang.reflect.Field consumerConnectionField = RabbitMqEventListenerRegistry.class.getDeclaredField("consumerConnection");
            consumerConnectionField.setAccessible(true);
            consumerConnectionField.set(registry, consumerConnection);

            java.lang.reflect.Field producerChannelField = RabbitMqEventListenerRegistry.class.getDeclaredField("producerChannel");
            producerChannelField.setAccessible(true);
            producerChannelField.set(registry, producerChannel);

            java.lang.reflect.Field producerAsyncChannelField = RabbitMqEventListenerRegistry.class.getDeclaredField("producerAsyncChannel");
            producerAsyncChannelField.setAccessible(true);
            producerAsyncChannelField.set(registry, producerAsyncChannel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
            public String[] registryBeanName() {
                return new String[]{"rabbitmq"};
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
