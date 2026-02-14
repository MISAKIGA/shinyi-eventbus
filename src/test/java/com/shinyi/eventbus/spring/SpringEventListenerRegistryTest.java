package com.shinyi.eventbus.spring;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.listener.BaseEventListener;
import com.shinyi.eventbus.registry.SpringEventListenerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SpringEventListenerRegistryTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ApplicationEventMulticaster eventMulticaster;

    private SpringEventListenerRegistry<EventModel<TestEvent>> registry;
    private Executor executor;

    @BeforeEach
    public void setUp() {
        executor = Executors.newFixedThreadPool(2);
        registry = new SpringEventListenerRegistry<>(applicationContext, "spring");
        registry.setThreadPool(executor);
        
        lenient().when(applicationContext.getBean(ApplicationEventMulticaster.class)).thenReturn(eventMulticaster);
    }

    @Test
    public void testGetEventBusType() {
        assertEquals(EventBusType.SPRING, registry.getEventBusType());
    }

    @Test
    public void testInitRegistryEventListener_shouldRegisterListener() {
        List<EventListener<EventModel<TestEvent>>> listeners = new ArrayList<>();
        EventListener<EventModel<TestEvent>> listener = createBaseTestListener();
        listeners.add(listener);

        registry.initRegistryEventListener(listeners);

        verify(eventMulticaster, times(1)).addApplicationListener(any(BaseEventListener.class));
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testPublish_shouldPublishEvent() {
        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);

        assertDoesNotThrow(() -> registry.publish(eventModel));

        verify(applicationContext, times(1)).publishEvent(any(EventModel.class));
    }

    @Test
    public void testPublishWithCallback_shouldTriggerCallback() {
        AtomicBoolean callbackTriggered = new AtomicBoolean(false);
        
        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEventCallback(new EventCallback() {
            @Override
            public void onSuccess(EventResult result) {
                callbackTriggered.set(true);
            }

            @Override
            public void onFailure(EventResult result, Throwable throwable) {
            }
        });

        registry.publish(eventModel);

        assertTrue(callbackTriggered.get());
    }

    @Test
    public void testPublishWithNullCallback_shouldNotThrowException() {
        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEventCallback(null);

        assertDoesNotThrow(() -> registry.publish(eventModel));
    }

    @Test
    public void testClose_shouldNotThrowException() {
        assertDoesNotThrow(() -> registry.close());
    }

    private EventListener<EventModel<TestEvent>> createBaseTestListener() {
        return new BaseEventListener<EventModel<TestEvent>>() {
            @Override
            public void onMessage(EventModel<TestEvent> event) {
                System.out.println("Received: " + event);
            }

            @Override
            public void onMessageBatch(List<EventModel<TestEvent>> events) {
            }

            @Override
            public void onApplicationEvent(PayloadApplicationEvent<EventModel<TestEvent>> event) {
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
        };
    }
}
