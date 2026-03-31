package com.shinyi.eventbus.listener;

import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.SerializeType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for MethodEventListener parameter type handling.
 *
 * 支持两种入参方式：
 * 1. List<EventModel<T>> - 直接传入 EventModel 列表，支持批量处理
 * 2. List<T> - 传入实体类型列表（如 List<String>），框架自动提取 entity
 */
public class MethodEventListenerRawStringTest {

    @Test
    public void testRawStringListener_withListStringParam() throws Exception {
        // 测试：RAW 模式 + entityType=String.class + List<String> 入参
        // 期望：收到 List<String>

        List<String> receivedStrings = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<String> events) {
                receivedStrings.addAll(events);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", String.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        String originalMessage = "Hello, World!";
        EventModel<Object> eventModel = EventModel.build("test-topic", originalMessage);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedStrings.size());
        assertEquals(originalMessage, receivedStrings.get(0));
        assertTrue(receivedStrings.get(0) instanceof String);
    }

    @Test
    public void testRawStringListener_withListEventModelParam() throws Exception {
        // 测试：RAW 模式 + entityType=String.class + List<EventModel> 入参
        // 期望：收到 List<EventModel>，用户自行从 EventModel 获取 entity

        List<EventModel<String>> receivedEvents = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<EventModel<String>> events) {
                receivedEvents.addAll(events);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", String.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        String originalMessage = "Hello, World!";
        EventModel<Object> eventModel = EventModel.build("test-topic", originalMessage);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedEvents.size());
        // EventModel itself should be received
        assertTrue(receivedEvents.get(0) instanceof EventModel);
        // But the entity inside should be the String
        assertEquals(originalMessage, receivedEvents.get(0).getEntity());
    }

    @Test
    public void testEventMode_withListMyEventParam() throws Exception {
        // 测试：EVENT 模式 + entityType=MyEvent.class + List<MyEvent> 入参
        // 期望：收到 List<MyEvent>

        class MyEvent {
            private String message;
            public MyEvent() {}
            public MyEvent(String message) { this.message = message; }
            public String getMessage() { return message; }
            public void setMessage(String message) { this.message = message; }
        }

        List<MyEvent> receivedEvents = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<MyEvent> events) {
                receivedEvents.addAll(events);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", MyEvent.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.EVENT.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        MyEvent originalEvent = new MyEvent("Test message");
        EventModel<Object> eventModel = EventModel.build("test-topic", originalEvent);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedEvents.size());
        assertEquals(originalEvent.getMessage(), receivedEvents.get(0).getMessage());
    }

    @Test
    public void testEventMode_withListEventModelParam() throws Exception {
        // 测试：EVENT 模式 + entityType=MyEvent.class + List<EventModel> 入参
        // 期望：收到 List<EventModel>

        class MyEvent {
            private String message;
            public MyEvent() {}
            public MyEvent(String message) { this.message = message; }
            public String getMessage() { return message; }
        }

        List<EventModel<MyEvent>> receivedEvents = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<EventModel<MyEvent>> events) {
                receivedEvents.addAll(events);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", MyEvent.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.EVENT.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        MyEvent originalEvent = new MyEvent("Test message");
        EventModel<Object> eventModel = EventModel.build("test-topic", originalEvent);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedEvents.size());
        // EventModel should be received
        assertTrue(receivedEvents.get(0) instanceof EventModel);
        // The entity inside should be the MyEvent
        assertEquals(originalEvent.getMessage(), receivedEvents.get(0).getEntity().getMessage());
    }

    @Test
    public void testRawByteArrayListener_withListByteArrayParam() throws Exception {
        // 测试：RAW 模式 + entityType=byte[].class + List<byte[]> 入参
        // 期望：收到 List<byte[]>

        List<byte[]> receivedBytes = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<byte[]> events) {
                receivedBytes.addAll(events);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", byte[].class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        byte[] originalBytes = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        EventModel<Object> eventModel = EventModel.build("test-topic", originalBytes);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedBytes.size());
        assertArrayEquals(originalBytes, receivedBytes.get(0));
    }
}