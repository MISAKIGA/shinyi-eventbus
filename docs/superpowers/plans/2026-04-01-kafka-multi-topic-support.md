# Kafka Multi-Topic Subscription Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Kafka consumer to subscribe to multiple topics via comma-separated string in configuration or @EventBusListener annotation.

**Architecture:** Single Consumer Multi-Topic approach - one KafkaConsumer subscribes to a list of topics. The consumer poll() returns records from all subscribed topics. Use record.topic() for accurate metrics and topic identification.

**Tech Stack:** Java 8+, Apache Kafka Client, Spring Boot

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java` | Parse comma-separated topics, subscribe to list, use record.topic() for metrics |
| `src/main/java/com/shinyi/eventbus/registry/KafkaMqEventListenerRegistry.java` | Same changes as above |
| `src/test/java/com/shinyi/eventbus/registry/KafkaMultiTopicTest.java` | **CREATE** - Integration test for multi-topic |
| `README.md` | Document multi-topic support |
| `README-CN.md` | Document multi-topic support (Chinese) |

---

## Task 1: Update OptimizedKafkaMqEventListenerRegistry

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

- [ ] **Step 1: Read the file to identify exact lines to change**

Key locations:
- Line 461-466: `initConsumer()` method where topic is parsed and subscribed
- Line 493: `MetricsHolder.increment()` where finalTopic is used

- [ ] **Step 2: Add topic parsing utility method**

Add a static utility method to parse comma-separated topics:

```java
/**
 * Parse comma-separated topic string into a list.
 * @param topics comma-separated topic string (e.g., "topic1,topic2,topic3")
 * @return list of trimmed, non-empty topics
 */
static List<String> parseTopics(String topics) {
    if (topics == null || topics.isEmpty()) {
        return Collections.emptyList();
    }
    return Arrays.stream(topics.split(","))
        .map(String::trim)
        .filter(t -> !t.isEmpty())
        .collect(Collectors.toList());
}
```

- [ ] **Step 3: Modify ConsumerHandler.initConsumer() - Topic parsing**

Find and replace (around line 461-466):

**Before:**
```java
String topic = listener.topic();
if (topic == null || topic.isEmpty()) {
    topic = defaultTopic;
}
final String finalTopic = topic;
consumer.subscribe(Collections.singletonList(finalTopic));
```

**After:**
```java
String topics = listener.topic();
if (topics == null || topics.isEmpty()) {
    topics = defaultTopic;
}
List<String> topicList = parseTopics(topics);
consumer.subscribe(topicList);
```

- [ ] **Step 4: Update MetricsHolder.increment() to use record.topic()**

Find and replace (around line 493):

**Before:**
```java
MetricsHolder.increment(registryBeanName, finalTopic, "events.consumed", 1);
```

**After:**
```java
MetricsHolder.increment(registryBeanName, record.topic(), "events.consumed", 1);
```

- [ ] **Step 5: Update error log to use record.topic()**

Around line 496:
**Before:**
```java
MetricsHolder.increment(registryBeanName, finalTopic, "events.failed", 1);
```

**After:**
```java
MetricsHolder.increment(registryBeanName, record.topic(), "events.failed", 1);
```

- [ ] **Step 6: Update executor thread naming for multi-topic**

Around line 468:
**Before:**
```java
ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "kafka-consumer-" + finalTopic));
```

**After:**
```java
String threadName = topicList.size() > 1
    ? "kafka-consumer-multi-" + topicList.get(0)
    : "kafka-consumer-" + topicList.get(0);
ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName));
```

- [ ] **Step 7: Compile and verify**

```bash
mvn compile -q
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java
git commit -m "feat(kafka): support comma-separated multi-topic subscription"
```

---

## Task 2: Update KafkaMqEventListenerRegistry (Legacy)

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/KafkaMqEventListenerRegistry.java`

- [ ] **Step 1: Read the file to identify exact lines**

Key locations:
- Line 121-128: Topic parsing and subscribe
- Line 150, 153: MetricsHolder.increment()

- [ ] **Step 2: Add parseTopics utility (or import from OptimizedKafkaMqEventListenerRegistry)**

Since KafkaMqEventListenerRegistry is deprecated and only for backward compatibility, add a simple inline method:

```java
private static List<String> parseTopics(String topics) {
    if (topics == null || topics.isEmpty()) {
        return Collections.emptyList();
    }
    return Arrays.stream(topics.split(","))
        .map(String::trim)
        .filter(t -> !t.isEmpty())
        .collect(Collectors.toList());
}
```

- [ ] **Step 3: Modify initConsumer() - Topic parsing (around line 121-128)**

**Before:**
```java
String topic = listener.topic();
if (topic == null || topic.isEmpty()) {
    topic = kafkaConnectConfig.getTopic();
}
final String finalTopic = topic;
consumer.subscribe(Collections.singletonList(finalTopic));
```

**After:**
```java
String topics = listener.topic();
if (topics == null || topics.isEmpty()) {
    topics = kafkaConnectConfig.getTopic();
}
List<String> topicList = parseTopics(topics);
consumer.subscribe(topicList);
```

- [ ] **Step 4: Update MetricsHolder.increment() calls to use record.topic()**

Line 150: `MetricsHolder.increment(registryBeanName, finalTopic, "events.consumed", 1);`
→ `MetricsHolder.increment(registryBeanName, record.topic(), "events.consumed", 1);`

Line 153: `MetricsHolder.increment(registryBeanName, finalTopic, "events.failed", 1);`
→ `MetricsHolder.increment(registryBeanName, record.topic(), "events.failed", 1);`

- [ ] **Step 5: Update executor thread naming (around line 128)**

**Before:**
```java
ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "kafka-consumer-" + finalTopic));
```

**After:**
```java
String threadName = topicList.size() > 1
    ? "kafka-consumer-multi-" + topicList.get(0)
    : "kafka-consumer-" + topicList.get(0);
ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName));
```

- [ ] **Step 6: Compile and verify**

```bash
mvn compile -q
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/registry/KafkaMqEventListenerRegistry.java
git commit -m "feat(kafka): support multi-topic in legacy registry"
```

---

## Task 3: Create Integration Test for Multi-Topic

**Files:**
- Create: `src/test/java/com/shinyi/eventbus/registry/KafkaMultiTopicTest.java`

- [ ] **Step 1: Check existing Kafka test structure**

Look at existing test in `src/test/java/com/shinyi/eventbus/registry/` to understand testcontainer setup pattern.

- [ ] **Step 2: Write multi-topic test**

```java
package com.shinyi.eventbus.registry;

import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for multi-topic Kafka subscription
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaMultiTopicTest {

    private static final String TOPIC_1 = "test-multi-topic-1";
    private static final String TOPIC_2 = "test-multi-topic-2";
    private static final String COMMA_SEPARATED_TOPICS = TOPIC_1 + "," + TOPIC_2;

    private OptimizedKafkaMqEventListenerRegistry<EventModel<?>> registry;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);

        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setTopic(COMMA_SEPARATED_TOPICS);  // comma-separated
        config.setGroupId("test-multi-topic-group");
        config.setEnableAutoCommit(true);

        registry = new OptimizedKafkaMqEventListenerRegistry<>(applicationContext, "kafka", config);
        registry.init();
    }

    @Test
    public void testParseTopics() {
        // Test comma-separated parsing
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        java.util.List<String> result = (java.util.List<String>) parseMethod.invoke(null, "topic1,topic2,topic3");
        assertEquals(3, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
        assertTrue(result.contains("topic3"));
    }

    @Test
    public void testParseTopicsWithWhitespace() {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        java.util.List<String> result = (java.util.List<String>) parseMethod.invoke(null, "topic1, topic2 , topic3");
        assertEquals(3, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
        assertTrue(result.contains("topic3"));
    }

    @Test
    public void testParseTopicsWithEmptyString() {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        java.util.List<String> result = (java.util.List<String>) parseMethod.invoke(null, "");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseTopicsWithNull() {
        java.lang.reflect.Method parseMethod = OptimizedKafkaMqEventListenerRegistry.class
            .getDeclaredMethod("parseTopics", String.class);
        parseMethod.setAccessible(true);

        java.util.List<String> result = (java.util.List<String>) parseMethod.invoke(null, (String) null);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 3: Run test to verify it compiles and runs**

```bash
mvn test -DskipTests=false -Dtest=KafkaMultiTopicTest -q
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/shinyi/eventbus/registry/KafkaMultiTopicTest.java
git commit -m "test(kafka): add multi-topic subscription test"
```

---

## Task 4: Update Documentation

**Files:**
- Modify: `README.md`
- Modify: `README-CN.md`

- [ ] **Step 1: Read README.md to find the Kafka Configuration section**

- [ ] **Step 2: Add multi-topic documentation to README.md**

In the Kafka Configuration section, add:

```yaml
# Kafka Configuration (Optional)
kafka:
  connect-configs:
    default-kafka:
      # Multi-topic support: comma-separated topics
      # Example: topic: "orders,payments,shipments"
      topic: my-topic

      # Or in @EventBusListener annotation:
      # @EventBusListener(name = "kafka", topic = "topic1,topic2,topic3")
```

Also add a new section "Multi-Topic Subscription":

```markdown
### Multi-Topic Subscription

Kafka supports subscribing to multiple topics using comma-separated strings.

**Configuration:**
```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        my-kafka:
          # Comma-separated topics
          topic: "orders,payments,notifications"
```

**Annotation:**
```java
@EventBusListener(
    name = "kafka",
    topic = "orders,payments,notifications",
    group = "my-consumer-group"
)
public void onEvent(EventModel<?> event) {
    // Handles messages from all three topics
    // Use event.getTopic() to distinguish source
}
```

**Notes:**
- All topics share the same consumer thread (single-threaded processing)
- For high-throughput scenarios, use separate listeners per topic
- EOS (Exactly-Once Semantics) works correctly across multiple topics
```

- [ ] **Step 3: Update README-CN.md with Chinese documentation**

Add the same documentation in Chinese.

- [ ] **Step 4: Commit**

```bash
git add README.md README-CN.md
git commit -m "docs: document multi-topic Kafka subscription support"
```

---

## Task 5: End-to-End Verification

- [ ] **Step 1: Run all tests**

```bash
mvn clean test -DskipTests=false -q
```

- [ ] **Step 2: Verify build**

```bash
mvn clean package -DskipTests -q
```

- [ ] **Step 3: Verify git log**

```bash
git log --oneline -10
```

Expected commits:
```
docs: document multi-topic Kafka subscription support
test(kafka): add multi-topic subscription test
feat(kafka): support multi-topic in legacy registry
feat(kafka): support comma-separated multi-topic subscription
```

---

## Summary

| Task | Description | Risk |
|------|-------------|------|
| 1 | Update OptimizedKafkaMqEventListenerRegistry | Medium - core change |
| 2 | Update KafkaMqEventListenerRegistry (legacy) | Low - deprecated class |
| 3 | Create KafkaMultiTopicTest | Low - new test |
| 4 | Update documentation (README.md, README-CN.md) | Low - docs only |
| 5 | End-to-end verification | - |

**Estimated Duration:** ~30 minutes
