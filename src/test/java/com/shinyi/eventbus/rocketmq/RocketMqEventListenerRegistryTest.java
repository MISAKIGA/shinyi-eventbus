package com.shinyi.eventbus.rocketmq;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.rocketmq.RocketMqConnectConfig;
import com.shinyi.eventbus.registry.RocketMqEventListenerRegistry;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.DefaultMQPushConsumer;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendCallback;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendResult;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.Message;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RocketMqEventListenerRegistryTest {

    @Mock
    private DefaultMQProducer mqProducer;

    @Mock
    private DefaultMQPushConsumer mqConsumer;

    @Mock
    private SendResult sendResult;

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
    public void testPublishSync_shouldSendMessage() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);

        when(mqProducer.send(any(Message.class))).thenReturn(sendResult);
        when(sendResult.getMsgId()).thenReturn("test-msg-id");
        when(sendResult.getMessageQueue()).thenReturn(new com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageQueue());

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setTags("test-tag");

        registry.publish(eventModel);

        verify(mqProducer, times(1)).send(any(Message.class));
    }

    @Test
    public void testPublishAsync_shouldSendMessageWithCallback() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);

        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(1, SendCallback.class);
            callback.onSuccess(sendResult);
            return null;
        }).when(mqProducer).send(any(Message.class), any(SendCallback.class));

        when(sendResult.getMsgId()).thenReturn("test-msg-id");
        when(sendResult.getMessageQueue()).thenReturn(new com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageQueue());

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setTags("test-tag");
        eventModel.setEnableAsync(true);

        EventCallback callback = mock(EventCallback.class);
        eventModel.setEventCallback(callback);

        registry.publish(eventModel);

        verify(mqProducer, times(1)).send(any(Message.class), any(SendCallback.class));
    }

    @Test
    public void testPublishWithNullCallback_shouldNotThrowException() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);

        when(mqProducer.send(any(Message.class))).thenReturn(sendResult);

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEventCallback(null);

        assertDoesNotThrow(() -> registry.publish(eventModel));
    }

    @Test
    public void testPublishAsync_withCallbackSuccess() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);

        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(1, SendCallback.class);
            callback.onSuccess(sendResult);
            return null;
        }).when(mqProducer).send(any(Message.class), any(SendCallback.class));

        when(sendResult.getMsgId()).thenReturn("test-msg-id");
        when(sendResult.getMessageQueue()).thenReturn(new com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageQueue());

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEnableAsync(true);

        EventCallback callback = mock(EventCallback.class);
        eventModel.setEventCallback(callback);

        registry.publish(eventModel);

        verify(callback, times(1)).onSuccess(any());
    }

    @Test
    public void testPublishAsync_withCallbackFailure() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);

        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(1, SendCallback.class);
            callback.onException(new RuntimeException("test error"));
            return null;
        }).when(mqProducer).send(any(Message.class), any(SendCallback.class));

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEnableAsync(true);

        EventCallback callback = mock(EventCallback.class);
        eventModel.setEventCallback(callback);

        registry.publish(eventModel);

        verify(callback, times(1)).onFailure(any(), any());
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testClose_shouldShutdownProducerAndConsumer() throws Exception {
        registry = new RocketMqEventListenerRegistry<>(applicationContext, "rocketmq", config);
        
        setRegistryProducer(registry);
        addConsumerToRegistry(registry);

        registry.close();

        verify(mqProducer, times(1)).shutdown();
    }

    private void setRegistryProducer(RocketMqEventListenerRegistry<EventModel<TestEvent>> registry) {
        try {
            java.lang.reflect.Field producerField = RocketMqEventListenerRegistry.class.getDeclaredField("mqProducer");
            producerField.setAccessible(true);
            producerField.set(registry, mqProducer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addConsumerToRegistry(RocketMqEventListenerRegistry<EventModel<TestEvent>> registry) {
        try {
            java.lang.reflect.Field consumersField = RocketMqEventListenerRegistry.class.getDeclaredField("mqConsumers");
            consumersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.MQConsumer> consumers = 
                (List<com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.MQConsumer>) consumersField.get(registry);
            consumers.add(mqConsumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
