package com.shinyi.eventbus.listener;

import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.SerializeType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for MethodEventListener parameter handling.
 */
public class MethodEventListenerEdgeCaseTest {

    @Test
    public void testSingleMessage_withSingleStringParam() throws Exception {
        // 测试：单消息入参（非 List）- String 类型
        // 方法签名: void onMessage(String event)

        List<String> receivedStrings = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(String event) {
                receivedStrings.add(event);
            }
        };

        Method method = listenerBean.getClass().getMethod("onMessage", String.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", String.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        String originalMessage = "Hello, Single Message!";
        EventModel<Object> eventModel = EventModel.build("test-topic", originalMessage);
        eventModel.setEventId("123");
        eventModel.setGroup("test-group");

        List<EventModel<Object>> messages = new ArrayList<>();
        messages.add(eventModel);

        // P0修复后：单消息入参（非List）应该正确提取entity，不再抛出异常
        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        // 修复后：应该正确接收到第一条消息的entity
        assertNull(thrown, "Should not throw with single-param method");
        assertEquals(1, receivedStrings.size());
        assertEquals("Hello, Single Message!", receivedStrings.get(0));
    }

    @Test
    public void testRawString_withRawListParam() throws Exception {
        // 测试：RAW 模式 + List<String> + entityType=String.class
        // 这是推荐的使用方式

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

        // 测试多条消息
        List<EventModel<Object>> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            EventModel<Object> em = EventModel.build("test-topic", "Message-" + i);
            em.setEventId(String.valueOf(i));
            messages.add(em);
        }

        Throwable thrown = null;
        try {
            listener.handle(messages);
        } catch (Throwable e) {
            thrown = e;
        }

        assertNull(thrown, "Should not throw");
        assertEquals(5, receivedStrings.size());
        assertEquals("Message-0", receivedStrings.get(0));
        assertEquals("Message-4", receivedStrings.get(4));
    }

    @Test
    public void testGenericTypeErasure_withListObjectParam() throws Exception {
        // 测试：泛型擦除情况 - 方法参数是 List<Object>
        // 由于泛型擦除，编译器只保留 List，无法知道具体类型

        List<Object> receivedItems = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<Object> events) {
                receivedItems.addAll(events);
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

        // 由于泛型擦除，paramElementType = Object.class
        // shouldExtractEntity = false（因为 Object.class 不是 String.class）
        // 所以会返回 EventModel 列表
        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedItems.size());
        // 由于泛型擦除，无法提取 entity，会传入 EventModel
        assertTrue(receivedItems.get(0) instanceof EventModel);
    }

    @Test
    public void testGenericTypeErasure_withNoGenericsListParam() throws Exception {
        // 测试：方法参数是原始 List 类型（无泛型）
        // 这种情况 paramElementType = null

        List<Object> receivedItems = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings({"rawtypes", "unused"})
            public void onMessage(List events) {
                receivedItems.addAll(events);
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

        // paramElementType = null（无法获取泛型类型）
        // shouldExtractEntity = false
        // 返回 EventModel 列表
        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedItems.size());
        assertTrue(receivedItems.get(0) instanceof EventModel);
    }

    @Test
    public void testGenericTypeErasure_withWildcardListParam() throws Exception {
        // 测试：方法参数是 List<?>（通配符）
        // 这种情况也无法获取具体类型

        List<Object> receivedItems = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<?> events) {
                receivedItems.addAll(events);
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

        // 通配符 List<?> 也无法获取具体类型
        // paramElementType = null
        // shouldExtractEntity = false
        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedItems.size());
        assertTrue(receivedItems.get(0) instanceof EventModel);
    }

    @Test
    public void testGenericTypeErasure_withRawEventModelList() throws Exception {
        // 测试：List<EventModel> 无泛型参数（泛型擦除）
        // 虽然 EventModel 是具体的，但泛型类型 T 被擦除了

        List<Object> receivedItems = new CopyOnWriteArrayList<>();

        Object listenerBean = new Object() {
            @SuppressWarnings({"rawtypes", "unused"})
            public void onMessage(List<EventModel> events) {
                receivedItems.addAll(events);
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

        // paramElementType = EventModel.class（因为 raw List<EventModel>）
        // shouldExtractEntity = false（EventModel 是父类）
        assertNull(thrown, "Should not throw");
        assertEquals(1, receivedItems.size());
        assertTrue(receivedItems.get(0) instanceof EventModel);
    }
}