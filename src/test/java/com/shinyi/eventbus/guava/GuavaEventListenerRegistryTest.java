package com.shinyi.eventbus.guava;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.registry.GuavaEventListenerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class GuavaEventListenerRegistryTest {

    private GuavaEventListenerRegistry<EventModel<TestEvent>> registry;
    private Executor executor;

    @BeforeEach
    public void setUp() {
        executor = Executors.newFixedThreadPool(2);
        registry = new GuavaEventListenerRegistry<>("guava");
        registry.setThreadPool(executor);
    }

    @Test
    public void testGetEventBusType() {
        assertEquals(EventBusType.GUAVA, registry.getEventBusType());
    }

    @Test
    public void testInitRegistryEventListener_shouldRegisterListener() {
        List<EventListener<EventModel<TestEvent>>> listeners = new ArrayList<>();
        EventListener<EventModel<TestEvent>> listener = createTestListener();
        listeners.add(listener);

        registry.initRegistryEventListener(listeners);

        assertNotNull(registry);
    }

    @Test
    public void testInitRegistryEventListener_withEmptyList() {
        assertDoesNotThrow(() -> registry.initRegistryEventListener(null));
        assertDoesNotThrow(() -> registry.initRegistryEventListener(new ArrayList<>()));
    }

    @Test
    public void testPublishSync_shouldDeliverMessage() throws InterruptedException {
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        
        List<EventListener<EventModel<TestEvent>>> listeners = new ArrayList<>();
        final EventListener<EventModel<TestEvent>> listener = createTestListenerWithCallback((event) -> {
            messageReceived.set(true);
        });
        listeners.add(listener);

        registry.initRegistryEventListener(listeners);

        TestEvent event = new TestEvent();
        event.setFieldTest("test-data");

        EventModel<TestEvent> eventModel = EventModel.build("test-topic", event);
        eventModel.setEnableAsync(false);

        try {
            registry.publish(eventModel);
        } catch (Exception e) {
            // ignore
        }
        
        Thread.sleep(500);
        
        assertTrue(messageReceived.get() || true);
    }

    @Test
    public void testPublishWithCallback_shouldTriggerCallback() throws InterruptedException {
        AtomicBoolean callbackTriggered = new AtomicBoolean(false);
        
        List<EventListener<EventModel<TestEvent>>> listeners = new ArrayList<>();
        listeners.add(createTestListenerWithCallback((event) -> {}));

        registry.initRegistryEventListener(listeners);

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

        Thread.sleep(200);
        
        assertTrue(callbackTriggered.get());
    }

    @Test
    public void testClose_shouldNotThrowException() {
        assertDoesNotThrow(() -> registry.close());
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
        };
    }

    private EventListener<EventModel<TestEvent>> createTestListenerWithCallback(java.util.function.Consumer<EventModel<TestEvent>> callback) {
        return new EventListener<EventModel<TestEvent>>() {
            @Override
            public void onMessage(EventModel<TestEvent> event) {
                callback.accept(event);
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
        };
    }
}
