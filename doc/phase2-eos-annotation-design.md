# Phase 2: EOS Annotation Design

## 1. Overview

Phase 2 adds annotation-driven Exactly-Once Semantics (EOS) to the event bus framework through the `@EventBusListener` annotation. This allows developers to enable EOS with a single attribute.

### 1.1 Design Goals

1. **Simple**: Single annotation attribute `exactlyOnce = true`
2. **Flexible**: Per-listener configuration via `commitBatchSize`
3. **Backward Compatible**: Default behavior unchanged
4. **Performant**: No overhead when EOS disabled

---

## 2. Current State

### 2.1 Existing Annotation

Current `@EventBusListener` attributes:
- `name` - Registry bean name
- `topic` - Topic to subscribe
- `group` - Consumer group
- `tags` - RocketMQ tags
- `deserializeType` - Serialization type
- `entityType` - Entity class for deserialization

### 2.2 Existing EventListener Interface

```java
public interface EventListener<T> extends java.util.EventListener {
    default Collection<String> registryBeanName() { return null; }
    String topic();
    void onMessage(T message) throws Exception;
    default String group() { return "DEFAULT"; }
    Class<?> entityType();
    // ... other defaults
}
```

### 2.3 Current Consumer Behavior

In `KafkaMqEventListenerRegistry.initConsumer()`:
- Uses `enable.auto.commit = true` (auto-commit)
- No manual offset management
- Messages processed once but offset committed automatically

---

## 3. EOS Annotation Design

### 3.1 New Annotation Attributes

**File: `src/main/java/com/shinyi/eventbus/anno/EventBusListener.java`**

```java
/**
 * Enable Exactly-Once Semantics (EOS) for this listener.
 *
 * When enabled:
 * - Producer uses idempotent producer (enable.idempotence=true)
 * - Consumer uses manual offset commit (enable.auto.commit=false)
 *
 * Default: false (backward compatible)
 */
boolean exactlyOnce() default false;

/**
 * Batch size for manual offset commit.
 *
 * Commits offset after processing this many messages.
 * Larger values = better throughput, higher risk of duplicates on failure.
 *
 * Default: 100
 */
int commitBatchSize() default 100;
```

### 3.2 EventListener Interface Updates

**File: `src/main/java/com/shinyi/eventbus/EventListener.java`**

```java
// Add to EventListener interface

/**
 * Enable Exactly-Once Semantics for this listener.
 * When true, enables idempotent producer and manual offset commit.
 */
default boolean exactlyOnce() { return false; }

/**
 * Number of messages to process before committing offset.
 * Only used when exactlyOnce() is true.
 */
default int commitBatchSize() { return 100; }
```

---

## 4. KafkaConnectConfig Changes

### 4.1 New EOS Configuration Properties

```java
// ==================== EOS (Exactly-Once Semantics) ====================

/**
 * Enable idempotent producer for exactly-once semantics.
 *
 * When true:
 * - enable.idempotence = true
 * - acks = "all" (forced)
 * - retries = MAX_VALUE (forced)
 * - max.in.flight.requests.per.connection = 5 (forced)
 *
 * Default: false
 */
private boolean enableIdempotence = false;

/**
 * Enable manual offset commit for consumer.
 *
 * When true:
 * - enable.auto.commit = false
 * - Manual commitSync() after processing batch
 *
 * Default: false
 */
private boolean enableManualCommit = false;

/**
 * Batch size for manual offset commit.
 * After processing this many messages, commit offsets.
 *
 * Default: 100
 */
private int commitBatchSize = 100;
```

### 4.2 Updated toProducerProperties()

```java
public Properties toProducerProperties() {
    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("key.serializer", keySerializer);
    props.put("value.serializer", valueSerializer);

    // Base performance settings
    props.put("batch.size", batchSize);
    props.put("linger.ms", lingerMs);
    props.put("buffer.memory", bufferMemory);
    props.put("compression.type", compressionType);

    // EOS: Idempotent producer (takes priority)
    if (enableIdempotence) {
        props.put("enable.idempotence", true);
        props.put("acks", "all");
        props.put("retries", Integer.MAX_VALUE);
        props.put("max.in.flight.requests.per.connection", 5);
        log.info("EOS: Idempotent producer enabled - acks=all, retries=MAX, max.in.flight=5");
    } else {
        // User configured values
        props.put("acks", acks);
        props.put("retries", retries);
        props.put("max.in.flight.requests.per.connection", maxInFlightRequestsPerConnection);
    }

    applySecurityProperties(props);
    return props;
}
```

### 4.3 Updated toConsumerProperties()

```java
public Properties toConsumerProperties() {
    Properties props = new Properties();
    // ... existing configurations ...

    // EOS: Manual offset commit
    if (enableManualCommit) {
        props.put("enable.auto.commit", false);
        log.info("EOS: Manual offset commit enabled - commitBatchSize={}", commitBatchSize);
    } else {
        props.put("enable.auto.commit", enableAutoCommit);
    }

    // ... rest of configurations ...
    return props;
}
```

---

## 5. Registry Integration

### 5.1 Per-Listener EOS Configuration

**Challenge**: Currently, `KafkaConnectConfig` is shared across all listeners in a registry.

**Solution**: Create a per-listener configuration holder that wraps the base config with listener-specific overrides.

```java
/**
 * Per-listener EOS configuration that wraps base KafkaConnectConfig.
 */
public class ListenerEosConfig {
    private final KafkaConnectConfig baseConfig;
    private final boolean exactlyOnce;
    private final int commitBatchSize;

    public ListenerEosConfig(KafkaConnectConfig baseConfig,
                             boolean exactlyOnce,
                             int commitBatchSize) {
        this.baseConfig = baseConfig;
        this.exactlyOnce = exactlyOnce;
        this.commitBatchSize = commitBatchSize;
    }

    public boolean isEnableIdempotence() {
        return exactlyOnce;  // Enable idempotence for EOS listeners
    }

    public boolean isEnableManualCommit() {
        return exactlyOnce;  // Enable manual commit for EOS listeners
    }

    public int getCommitBatchSize() {
        return commitBatchSize;
    }
}
```

### 5.2 Updated Consumer Loop

**File: `KafkaMqEventListenerRegistry.java`**

```java
// New fields for EOS
private Map<KafkaConsumer<String, byte[]>, OffsetCommitState> offsetStates = new ConcurrentHashMap<>();

// Inner class for offset tracking
private static class OffsetCommitState {
    Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new ConcurrentHashMap<>();
    int processedCount = 0;
}

private void initConsumer(com.shinyi.eventbus.EventListener<T> listener) {
    // ... existing setup code ...

    // Check if EOS is enabled for this listener
    boolean eosEnabled = listener.exactlyOnce();
    int batchSize = listener.commitBatchSize();

    ExecutorService executor = Executors.newSingleThreadExecutor(r ->
        new Thread(r, "kafka-consumer-eos-" + finalTopic));
    executorSet.add(executor);

    final com.shinyi.eventbus.EventListener<T> finalListener = listener;
    executor.submit(() -> {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

                    for (ConsumerRecord<String, byte[]> record : records) {
                        try {
                            // Process message
                            EventModel<?> eventModel = deserialize(record.value(),
                                record.offset() + "", finalListener);
                            finalListener.onMessage((T) eventModel);

                            // EOS: Track offset for manual commit
                            if (eosEnabled) {
                                trackOffsetAndCommit(consumer, record, batchSize);
                            }
                        } catch (Exception e) {
                            log.warn("Message processing failed: " + e.getMessage(), e);
                        }
                    }
                } catch (WakeupException e) {
                    break;
                }
            }
        } finally {
            // EOS: Final commit on shutdown
            if (eosEnabled) {
                commitPendingOffsets(consumer);
            }
            consumer.close();
        }
    });
}

private void trackOffsetAndCommit(KafkaConsumer<String, byte[]> consumer,
                                   ConsumerRecord<String, byte[]> record,
                                   int batchSize) {
    OffsetCommitState state = offsetStates.get(consumer);
    if (state == null) {
        state = new OffsetCommitState();
        offsetStates.put(consumer, state);
    }

    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
    state.pendingOffsets.put(tp, new OffsetAndMetadata(record.offset() + 1));

    if (state.pendingOffsets.size() >= batchSize) {
        commitPendingOffsets(consumer);
    }
}

private void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer) {
    OffsetCommitState state = offsetStates.get(consumer);
    if (state != null && !state.pendingOffsets.isEmpty()) {
        try {
            consumer.commitSync(new HashMap<>(state.pendingOffsets));
            log.debug("Committed offsets: {}", state.pendingOffsets.size());
            state.pendingOffsets.clear();
        } catch (Exception e) {
            log.error("Failed to commit offsets: " + e.getMessage(), e);
        }
    }
}
```

---

## 6. Usage Examples

### 6.1 Basic EOS Usage

```java
@EventBusListener(
    topic = "payment-events",
    group = "payment-processor",
    entityType = PaymentEvent.class,
    exactlyOnce = true
)
public class PaymentEventListener implements EventListener<PaymentEvent> {

    @Override
    public void onMessage(PaymentEvent message) throws Exception {
        // Process payment - exactly-once guaranteed
        paymentService.process(message);
    }
}
```

### 6.2 Advanced EOS with Custom Batch Size

```java
@EventBusListener(
    topic = "order-events",
    group = "order-processor",
    entityType = OrderEvent.class,
    exactlyOnce = true,
    commitBatchSize = 50  // Smaller batch = less duplicate risk
)
public class OrderEventListener implements EventListener<OrderEvent> {

    @Override
    public void onMessage(OrderEvent message) throws Exception {
        orderService.process(message);
    }
}
```

### 6.3 YAML Configuration (Alternative to Annotation)

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        eos-kafka:
          bootstrap-servers: localhost:9092
          topic: payment-events
          # Global EOS settings (can be overridden per listener)
          enable-idempotence: true
          enable-manual-commit: true
          commit-batch-size: 100
```

### 6.4 Mixing EOS and Non-EOS Listeners

```java
// Listener with EOS (payment events - critical)
@EventBusListener(
    topic = "payment-events",
    group = "payment-group",
    entityType = PaymentEvent.class,
    exactlyOnce = true
)
public class PaymentListener implements EventListener<PaymentEvent> { }

// Listener without EOS (logging - performance critical, at-least-once OK)
@EventBusListener(
    topic = "audit-log",
    group = "audit-group",
    entityType = AuditLog.class,
    exactlyOnce = false  // Default
)
public class AuditListener implements EventListener<AuditLog> { }
```

---

## 7. EOS Configuration Properties

### 7.1 Producer-Side EOS

| Kafka Property | Value when EOS enabled | Description |
|----------------|------------------------|-------------|
| `enable.idempotence` | `true` | Enable idempotent producer |
| `acks` | `"all"` | All replicas must acknowledge |
| `retries` | `Integer.MAX_VALUE` | Maximum retries |
| `max.in.flight.requests.per.connection` | `5` | Required for idempotence |

### 7.2 Consumer-Side EOS

| Kafka Property | Value when EOS enabled | Description |
|----------------|------------------------|-------------|
| `enable.auto.commit` | `false` | Disable auto-commit |
| Manual commit | After `commitBatchSize` messages | Batch commit |

---

## 8. Implementation Architecture

### 8.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    EventListenerRegistryManager              │
│  (Scans @EventBusListener, creates registries)              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              KafkaMqEventListenerRegistry                    │
│  - Reads exactlyOnce() from EventListener                    │
│  - Creates ListenerEosConfig per listener                    │
│  - Manages offset tracking per consumer                      │
└─────────────────────────────────────────────────────────────┘
          │                           │
          ▼                           ▼
┌──────────────────┐      ┌──────────────────────────┐
│  KafkaProducer   │      │     KafkaConsumer        │
│  (EOS enabled)    │      │  (EOS enabled)           │
│  - Idempotent    │      │  - Manual commit         │
│  - acks=all      │      │  - Batch offset commit   │
└──────────────────┘      └──────────────────────────┘
```

### 8.2 Offset Tracking Flow

```
1. consumer.poll() returns records
           │
           ▼
2. For each record:
   - listener.onMessage(record)
   - If EOS: track offset in pendingOffsets map
           │
           ▼
3. When pendingOffsets.size() >= commitBatchSize:
   - consumer.commitSync(pendingOffsets)
   - clear pendingOffsets
           │
           ▼
4. On shutdown:
   - Commit any remaining pending offsets
   - Close consumer
```

---

## 9. Failure Handling

### 9.1 Message Processing Failure

```java
// In consumer loop
try {
    finalListener.onMessage((T) eventModel);
    // Track offset only on success
    if (eosEnabled) {
        trackOffsetAndCommit(consumer, record, batchSize);
    }
} catch (Exception e) {
    log.error("Message processing failed permanently: topic={}, partition={}, offset={}",
        record.topic(), record.partition(), record.offset(), e);
    // DO NOT commit offset - message will be reprocessed after rebalance
}
```

### 9.2 Offset Commit Failure

```java
private void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer) {
    // ... existing code ...
    try {
        consumer.commitSync(new HashMap<>(state.pendingOffsets));
        state.pendingOffsets.clear();
    } catch (CommitFailedException e) {
        log.error("Offset commit failed, will retry: " + e.getMessage(), e);
        // Do NOT clear - will retry on next batch
    } catch (Exception e) {
        log.error("Unexpected error during offset commit: " + e.getMessage(), e);
        // Keep offsets for retry
    }
}
```

### 9.3 Shutdown Protection

```java
@Override
public void close() throws Exception {
    // Stop consumers first to stop processing new messages
    for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
        consumer.wakeup();  // Wake up from poll()
    }

    // Wait briefly for in-flight processing
    Thread.sleep(1000);

    // Commit pending offsets for EOS consumers
    for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
        commitPendingOffsets(consumer);
    }

    // Shutdown executors
    for (ExecutorService executor : executorSet) {
        executor.shutdownNow();
    }

    // Close producer
    if (producer != null) {
        producer.close();
    }
}
```

---

## 10. Acceptance Criteria

### 10.1 Functional Requirements

| Requirement | Test Method | Expected Result |
|-------------|-------------|-----------------|
| Annotation with `exactlyOnce=true` enables EOS | Unit test | Producer/consumer configured correctly |
| `commitBatchSize` controls commit frequency | Unit test | Offset tracked correctly |
| Non-EOS listeners unchanged | Regression test | Existing tests pass |
| Producer idempotence verified | Integration test | No duplicate sends |
| Consumer manual commit verified | Integration test | Offset advances correctly |

### 10.2 Performance Requirements

| Metric | Target | Measurement |
|--------|--------|-------------|
| EOS Producer overhead | < 5% vs non-EOS | Benchmark |
| Consumer throughput | 30,000+ msg/s | Benchmark |
| Memory overhead | Minimal | Profiling |

### 10.3 Reliability Requirements

| Scenario | Expected Behavior |
|----------|-------------------|
| Message processed, app crashes | Offset committed on restart = no duplicates |
| Message processing fails | Offset not committed = at-least-once on retry |
| Broker unavailable | Retry with backoff, eventually fails gracefully |
| Consumer rebalance | Pending offsets committed before rebalance |

---

## 11. Test Plan

### 11.1 Unit Tests

```java
@Test
public void testExactlyOnceAnnotation_parsesCorrectly() {
    // Verify EventListener.exactlyOnce() returns annotation value
}

@Test
public void testCommitBatchSizeAnnotation_parsesCorrectly() {
    // Verify EventListener.commitBatchSize() returns annotation value
}

@Test
public void testEosConfig_enableIdempotenceWhenExactlyOnce() {
    // Verify enableIdempotence=true when exactlyOnce=true
}

@Test
public void testEosConfig_enableManualCommitWhenExactlyOnce() {
    // Verify enableManualCommit=true when exactlyOnce=true
}
```

### 11.2 Integration Tests

```java
@Test
public void testEosEndToEnd_100kMessages_0Duplicates() {
    // Send 100K unique messages
    // Consume with EOS enabled
    // Verify exactly 100K unique messages received
}

@Test
public void testEosCrashRecovery_noMessageLoss() {
    // Process half the messages
    // Simulate crash (kill consumer)
    // Restart consumer
    // Verify all messages processed once
}

@Test
public void testEosConsumerRebalance_correctOffset() {
    // Process some messages
    // Trigger rebalance
    // Verify no duplicates or gaps
}
```

---

## 12. Implementation Tasks

| Task | File | Priority | Status |
|------|------|----------|--------|
| T1: Add `exactlyOnce()` to @EventBusListener | EventBusListener.java | P0 | Pending |
| T2: Add `commitBatchSize()` to @EventBusListener | EventBusListener.java | P0 | Pending |
| T3: Add EOS properties to EventListener interface | EventListener.java | P0 | Pending |
| T4: Add EOS properties to KafkaConnectConfig | KafkaConnectConfig.java | P0 | Pending |
| T5: Update toProducerProperties() for EOS | KafkaConnectConfig.java | P0 | Pending |
| T6: Update toConsumerProperties() for EOS | KafkaConnectConfig.java | P0 | Pending |
| T7: Create ListenerEosConfig wrapper class | New file | P1 | Pending |
| T8: Update initConsumer() for EOS | KafkaMqEventListenerRegistry.java | P0 | Pending |
| T9: Add offset tracking logic | KafkaMqEventListenerRegistry.java | P0 | Pending |
| T10: Add shutdown protection | KafkaMqEventListenerRegistry.java | P0 | Pending |
| T11: Add unit tests | KafkaProducerTest.java | P0 | Pending |
| T12: Add integration tests | KafkaEosIntegrationTest.java | P0 | Pending |

---

**Document Version**: 1.0
**Created Date**: 2026-03-19
**Status**: Ready for Implementation
**Dependencies**: Phase 0 (Performance) and Phase 1 (Security) completed
