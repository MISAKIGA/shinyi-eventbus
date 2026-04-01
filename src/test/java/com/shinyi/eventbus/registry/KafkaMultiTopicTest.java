package com.shinyi.eventbus.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for multi-topic parsing functionality
 */
public class KafkaMultiTopicTest {

    @Test
    public void testParseTopics_singleTopic() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        List<String> result = (List<String>) parseMethod.invoke(null, "topic1");
        assertEquals(1, result.size());
        assertEquals("topic1", result.get(0));
    }

    @Test
    public void testParseTopics_multipleTopics() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        List<String> result = (List<String>) parseMethod.invoke(null, "topic1,topic2,topic3");
        assertEquals(3, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
        assertTrue(result.contains("topic3"));
    }

    @Test
    public void testParseTopics_withWhitespace() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        List<String> result = (List<String>) parseMethod.invoke(null, "topic1, topic2 , topic3");
        assertEquals(3, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
        assertTrue(result.contains("topic3"));
    }

    @Test
    public void testParseTopics_emptyString() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        List<String> result = (List<String>) parseMethod.invoke(null, "");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseTopics_null() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        List<String> result = (List<String>) parseMethod.invoke(null, (String) null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseTopics_withEmptyParts() throws Exception {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        // Empty parts should be filtered out
        List<String> result = (List<String>) parseMethod.invoke(null, "topic1,,topic2,");
        assertEquals(2, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
    }
}