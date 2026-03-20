# Phase 3: Advanced Features Design

## 1. Overview

Phase 3 covers advanced Kafka features for high-throughput, production-grade deployments:
- Producer Pool for horizontal scaling
- Consumer Pool for parallel consumption
- Latency Tracking & Benchmarking
- Dead Letter Queue (DLQ) support

---

## 2. Producer Pool for High Throughput

### 2.1 Problem Statement

Single KafkaProducer may become a bottleneck at very high throughput (>100,000 msg/s).

### 2.2 Solution: Producer Pool

```java
package com.shinyi.eventbus.kafka.pool;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka Producer Pool for high-throughput scenarios.
 *
 * Uses round-robin distribution across multiple producers.
 * Thread-safe for concurrent sending.
 */
@Slf4j
public class KafkaProducerPool {

    private final List<KafkaProducer<String, byte[]>> producers;
    private final AtomicLong roundRobinCounter = new AtomicLong(0);

    /**
     * Create a producer pool with specified size.
     *
     * @param producerProperties Base producer properties
     * @param poolSize Number of producers in pool
     */
    public KafkaProducerPool(Properties producerProperties, int poolSize) {
        this.producers = new CopyOnWriteArrayList<>();

        for (int i = 0; i < poolSize; i++) {
            // Clone properties and add unique client.id per producer
            Properties props = (Properties) producerProperties.clone();
            String baseClientId = props.getProperty("client.id", "producer");
            props.setProperty("client.id", baseClientId + "-" + i);
            producers.add(new KafkaProducer<>(props));
        }

        log.info("KafkaProducerPool initialized with {} producers", poolSize);
    }

    /**
     * Get next producer in round-robin fashion.
     */
    public KafkaProducer<String, byte[]> getNextProducer() {
        int index = (int) (roundRobinCounter.getAndIncrement() % producers.size());
        return producers.get(index);
    }

    /**
     * Send record using next available producer.
     */
    public RecordMetadata send(ProducerRecord<String, byte[]> record) throws Exception {
        return getNextProducer().send(record).get();
    }

    /**
     * Send record asynchronously with callback.
     */
    public void sendAsync(ProducerRecord<String, byte[]> record,
                          org.apache.kafka.clients.producer.Callback callback) {
        getNextProducer().send(record, callback);
    }

    /**
     * Close all producers in the pool.
     */
    public void close() {
        for (KafkaProducer<String, byte[]> producer : producers) {
            try {
                producer.close();
            } catch (Exception e) {
                log.warn("Error closing producer: " + e.getMessage());
            }
        }
        producers.clear();
        log.info("KafkaProducerPool closed");
    }

    /**
     * Get pool size.
     */
    public int getPoolSize() {
        return producers.size();
    }
}
```

### 2.3 Configuration

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        pooled-kafka:
          bootstrap-servers: localhost:9092
          # Producer pool configuration
          producer-pool-size: 4  # Number of producers (default: 1)
```

### 2.4 Registry Integration

```java
// In KafkaMqEventListenerRegistry

private KafkaProducerPool producerPool;

public void init() {
    // ... existing initialization ...

    int poolSize = kafkaConnectConfig.getProducerPoolSize();
    if (poolSize > 1) {
        Properties producerProps = kafkaConnectConfig.toProducerProperties();
        producerPool = new KafkaProducerPool(producerProps, poolSize);
        log.info("Using producer pool with {} producers", poolSize);
    } else {
        // Single producer (existing behavior)
        producer = new KafkaProducer<>(kafkaConnectConfig.toProducerProperties());
    }
}

@Override
public void publish(T eventModel) {
    // ... existing code ...

    if (producerPool != null) {
        // Use pool
        if (eventModel.isEnableAsync()) {
            producerPool.sendAsync(record, (metadata, exception) -> {
                // ... existing callback handling ...
            });
        } else {
            metadata = producerPool.send(record);
        }
    } else {
        // Use single producer (existing behavior)
        // ... existing code ...
    }
}

@Override
public void close() throws Exception {
    if (producerPool != null) {
        producerPool.close();
    } else if (producer != null) {
        producer.close();
    }
    // ... rest of cleanup ...
}
```

### 2.5 Pool Size Guidelines

| Throughput Target | Pool Size | Notes |
|-------------------|-----------|-------|
| < 50,000 msg/s | 1 | Single producer sufficient |
| 50,000-100,000 msg/s | 2 | 2x producers |
| 100,000-200,000 msg/s | 4 | 4x producers |
| > 200,000 msg/s | 8+ | Profile and tune |

---

## 3. Consumer Pool for Parallel Consumption

### 3.1 Problem Statement

Single consumer is limited by partition count. If topic has only 1 partition, consumer throughput is limited.

### 3.2 Solution: Consumer Pool with Consumer Groups

```java
package com.shinyi.eventbus.kafka.pool;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kafka Consumer Pool for parallel consumption.
 *
 * Each consumer in the pool gets its own consumer group ID,
 * allowing them to consume from all partitions independently.
 */
@Slf4j
public class KafkaConsumerPool<T> {

    private final List<KafkaConsumer<String, byte[]>> consumers;
    private final List<ExecutorService> executors;
    private final String baseGroupId;
    private final String topic;
    private final Properties baseConsumerProps;

    public KafkaConsumerPool(Properties baseConsumerProps,
                             String topic,
                             int poolSize) {
        this.baseConsumerProps = baseConsumerProps;
        this.topic = topic;
        this.consumers = new CopyOnWriteArrayList<>();
        this.executors = new CopyOnWriteArrayList<>();
        this.baseGroupId = baseConsumerProps.getProperty(ConsumerConfig.GROUP_ID_CONFIG);

        for (int i = 0; i < poolSize; i++) {
            // Create unique group ID for each consumer
            Properties props = (Properties) baseConsumerProps.clone();
            props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, baseGroupId + "-" + i);
            // Disable auto-commit for EOS mode (handled per consumer)
            props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

            KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
            consumer.subscribe(Collections.singletonList(topic));
            consumers.add(consumer);

            ExecutorService executor = Executors.newSingleThreadExecutor(r ->
                new Thread(r, "kafka-consumer-pool-" + i));
            executors.add(executor);
        }

        log.info("KafkaConsumerPool initialized with {} consumers for topic: {}",
                 poolSize, topic);
    }

    /**
     * Start consuming with all consumers in the pool.
     */
    public void startConsuming(java.util.function.Consumer<org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>> recordHandler) {
        for (int i = 0; i < consumers.size(); i++) {
            final int consumerIndex = i;
            final KafkaConsumer<String, byte[]> consumer = consumers.get(i);
            final ExecutorService executor = executors.get(i);

            executor.submit(() -> {
                try {
                    log.info("Consumer {} starting", consumerIndex);
                    while (!Thread.currentThread().isInterrupted()) {
                        var records = consumer.poll(Duration.ofMillis(1000));
                        for (var record : records) {
                            try {
                                recordHandler.accept(record);
                                // Manual offset commit per record
                                consumer.commitSync();
                            } catch (Exception e) {
                                log.error("Error processing record: " + e.getMessage(), e);
                            }
                        }
                    }
                } finally {
                    consumer.close();
                }
            });
        }
    }

    /**
     * Stop all consumers.
     */
    public void stop() {
        for (KafkaConsumer<String, byte[]> consumer : consumers) {
            consumer.wakeup();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        log.info("KafkaConsumerPool stopped");
    }
}
```

### 3.3 Configuration

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        parallel-kafka:
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
          # Consumer pool configuration
          consumer-pool-size: 4  # Number of parallel consumers
```

---

## 4. Latency Tracker & Benchmarking

### 4.1 Latency Tracker Implementation

```java
package com.shinyi.eventbus.metrics;

import lombok.Data;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Latency tracker with histogram buckets for percentile calculation.
 */
@Data
public class LatencyTracker {

    // Histogram buckets for latencies (in milliseconds)
    private static final int[] BUCKETS = {
        1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000
    };

    private final LongAdder[] latencyBuckets = new LongAdder[BUCKETS.length];
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);

    public LatencyTracker() {
        for (int i = 0; i < BUCKETS.length; i++) {
            latencyBuckets[i] = new LongAdder();
        }
    }

    /**
     * Record a latency measurement.
     */
    public void recordLatency(long latencyNanos) {
        totalCount.increment();
        totalLatencyNanos.add(latencyNanos);

        // Update max
        long currentMax = maxLatencyNanos.get();
        while (latencyNanos > currentMax) {
            maxLatencyNanos.compareAndSet(currentMax, latencyNanos);
            currentMax = maxLatencyNanos.get();
        }

        // Find bucket and increment
        int bucket = getBucket(latencyNanos);
        if (bucket >= 0 && bucket < latencyBuckets.length) {
            latencyBuckets[bucket].increment();
        }
    }

    private int getBucket(long latencyNanos) {
        long latencyMs = latencyNanos / 1_000_000;
        for (int i = 0; i < BUCKETS.length; i++) {
            if (latencyMs <= BUCKETS[i]) {
                return i;
            }
        }
        return BUCKETS.length - 1;
    }

    /**
     * Get latency statistics.
     */
    public LatencyStats getStats() {
        LatencyStats stats = new LatencyStats();
        stats.setCount(totalCount.sum());
        if (stats.getCount() > 0) {
            stats.setMeanNanos(totalLatencyNanos.sum() / stats.getCount());
            stats.setMaxNanos(maxLatencyNanos.get());
        }
        return stats;
    }

    /**
     * Calculate percentile.
     */
    public long getPercentile(double percentile) {
        long count = totalCount.sum();
        if (count == 0) return 0;

        long targetCount = (long) (count * percentile / 100);
        long cumulative = 0;

        for (int i = 0; i < latencyBuckets.length; i++) {
            cumulative += latencyBuckets[i].sum();
            if (cumulative >= targetCount) {
                return BUCKETS[i] * 1_000_000L; // Return in nanos
            }
        }
        return BUCKETS[BUCKETS.length - 1] * 1_000_000L;
    }

    @Data
    public static class LatencyStats {
        private long count;
        private long meanNanos;
        private long maxNanos;
    }
}
```

### 4.2 Usage in Registry

```java
// In KafkaMqEventListenerRegistry
private final LatencyTracker producerLatency = new LatencyTracker();
private final LatencyTracker consumerLatency = new LatencyTracker();

@Override
public void publish(T eventModel) {
    long startNanos = System.nanoTime();
    try {
        // ... existing publish logic ...
    } finally {
        producerLatency.recordLatency(System.nanoTime() - startNanos);
    }
}
```

### 4.3 Benchmark Results Output

```java
/**
 * Print benchmark results.
 */
public void printBenchmarkReport() {
    LatencyStats producerStats = producerLatency.getStats();

    System.out.println("\n========================================");
    System.out.println("         BENCHMARK REPORT");
    System.out.println("========================================");
    System.out.printf("Producer:\n");
    System.out.printf("  Count:      %d\n", producerStats.getCount());
    System.out.printf("  Mean:       %.2f ms\n", producerStats.getMeanNanos() / 1_000_000.0);
    System.out.printf("  Max:        %.2f ms\n", producerStats.getMaxNanos() / 1_000_000.0);
    System.out.printf("  P50:        %.2f ms\n", producerLatency.getPercentile(50) / 1_000_000.0);
    System.out.printf("  P90:        %.2f ms\n", producerLatency.getPercentile(90) / 1_000_000.0);
    System.out.printf("  P95:        %.2f ms\n", producerLatency.getPercentile(95) / 1_000_000.0);
    System.out.printf("  P99:        %.2f ms\n", producerLatency.getPercentile(99) / 1_000_000.0);
    System.out.println("========================================\n");
}
```

---

## 5. Dead Letter Queue (DLQ) Support

### 5.1 DLQ Strategy

When message processing fails permanently, send to DLQ instead of losing the message.

### 5.2 DLQ Configuration

```java
// Add to KafkaConnectConfig
private String deadLetterTopic = "DLQ";  // Topic name suffix

private int maxRetries = 3;  // Max retry attempts before DLQ

private long retryBackoffMs = 1000;  // Initial backoff
```

### 5.3 DLQ Handler Implementation

```java
package com.shinyi.eventbus.kafka.dlq;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;

/**
 * Dead Letter Queue handler for failed messages.
 */
@Slf4j
public class DeadLetterQueueHandler {

    private final KafkaProducer<String, byte[]> dlqProducer;
    private final String dlqTopicSuffix;
    private final int maxRetries;

    public DeadLetterQueueHandler(KafkaProducer<String, byte[]> producer,
                                   String dlqTopicSuffix,
                                   int maxRetries) {
        this.dlqProducer = producer;
        this.dlqTopicSuffix = dlqTopicSuffix != null ? dlqTopicSuffix : "DLQ";
        this.maxRetries = maxRetries;
    }

    /**
     * Send failed message to DLQ.
     */
    public void sendToDlq(Object originalMessage,
                          byte[] originalPayload,
                          String originalTopic,
                          Exception failureReason) {

        String dlqTopic = originalTopic + "-" + dlqTopicSuffix;

        // Create DLQ message with metadata
        String dlqKey = "failed-" + System.currentTimeMillis();
        String dlqValue = buildDlqPayload(originalPayload, originalTopic, failureReason);

        ProducerRecord<String, byte[]> dlqRecord = new ProducerRecord<>(
            dlqTopic,
            dlqKey,
            dlqValue.getBytes()
        );

        dlqProducer.send(dlqRecord, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to send message to DLQ: topic={}, error={}",
                         dlqTopic, exception.getMessage());
            } else {
                log.info("Message sent to DLQ: topic={}, partition={}, offset={}",
                        metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }

    private String buildDlqPayload(byte[] originalPayload,
                                    String originalTopic,
                                    Exception failureReason) {
        return String.format(
            "{\"originalTopic\":\"%s\",\"failureReason\":\"%s\",\"timestamp\":%d,\"payload\":\"%s\"}",
            originalTopic,
            failureReason.getMessage(),
            System.currentTimeMillis(),
            new String(originalPayload)
        );
    }
}
```

### 5.4 DLQ Configuration Example

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        dlq-kafka:
          bootstrap-servers: localhost:9092
          topic: payment-events
          # Dead Letter Queue configuration
          dead-letter-topic: DLQ  # Suffix for DLQ topic
          max-retries: 3          # Retry attempts before DLQ
          retry-backoff-ms: 1000 # Initial backoff between retries
```

---

## 6. Configuration API

### 6.1 Fluent Builder Pattern

```java
// KafkaConnectConfig builder
KafkaConnectConfig config = KafkaConnectConfig.builder()
    .bootstrapServers("localhost:9092")
    .topic("my-topic")
    .groupId("my-group")
    .batchSize(65536)
    .lingerMs(10)
    .compressionType("snappy")
    .enableIdempotence(true)
    .enableManualCommit(true)
    .commitBatchSize(100)
    .producerPoolSize(4)
    .consumerPoolSize(4)
    .build();
```

### 6.2 Implementation

```java
// Add to KafkaConnectConfig
@Builder
public static KafkaConnectConfigBuilder builder() {
    return new KafkaConnectConfigBuilder();
}

public static class KafkaConnectConfigBuilder {
    // Lombok generates builder
}

@Data
class KafkaConnectConfigBuilder {
    private String bootstrapServers;
    private String topic;
    private String groupId;
    // ... all fields with setters
}
```

**Note**: Requires Lombok `@Builder` annotation on the class.

---

## 7. Implementation Priority

| Phase | Feature | Complexity | Priority |
|-------|---------|------------|----------|
| 3a | Producer Pool | Medium | P1 |
| 3b | Consumer Pool | Medium | P1 |
| 3c | Latency Tracker | Low | P2 |
| 3d | DLQ Support | Medium | P2 |

---

## 8. Acceptance Criteria

### 8.1 Producer Pool

| Requirement | Test Method | Expected Result |
|-------------|-------------|-----------------|
| Pool size configurable | Unit test | Pool created with specified size |
| Round-robin distribution | Load test | Even distribution across producers |
| Thread-safe | Concurrency test | No race conditions |
| Clean shutdown | Integration test | All producers closed |

### 8.2 Consumer Pool

| Requirement | Test Method | Expected Result |
|-------------|-------------|-----------------|
| Each consumer has unique group ID | Unit test | groupId, groupId-1, groupId-2... |
| Parallel consumption | Integration test | Multiple threads consuming |
| Clean shutdown | Integration test | All consumers closed properly |

### 8.3 Latency Tracker

| Requirement | Test Method | Expected Result |
|-------------|-------------|-----------------|
| P50/P90/P99 calculation | Unit test | Correct percentile values |
| High concurrency | Load test | Accurate under load |

### 8.4 DLQ

| Requirement | Test Method | Expected Result |
|-------------|-------------|-----------------|
| Failed message sent to DLQ | Integration test | Message in DLQ topic |
| DLQ payload contains metadata | Unit test | Original topic and error present |
| DLQ topic naming | Integration test | `originalTopic-DLQ` |

---

## 9. Implementation Tasks

| Task | File | Priority |
|------|------|----------|
| T1: Create KafkaProducerPool | New: KafkaProducerPool.java | P1 |
| T2: Add producer-pool-size config | KafkaConnectConfig.java | P1 |
| T3: Update Registry for producer pool | KafkaMqEventListenerRegistry.java | P1 |
| T4: Create KafkaConsumerPool | New: KafkaConsumerPool.java | P1 |
| T5: Add consumer-pool-size config | KafkaConnectConfig.java | P1 |
| T6: Update Registry for consumer pool | KafkaMqEventListenerRegistry.java | P1 |
| T7: Create LatencyTracker | New: LatencyTracker.java | P2 |
| T8: Integrate latency tracking | KafkaMqEventListenerRegistry.java | P2 |
| T9: Create DeadLetterQueueHandler | New: DeadLetterQueueHandler.java | P2 |
| T10: Add DLQ configuration | KafkaConnectConfig.java | P2 |
| T11: Integrate DLQ handler | KafkaMqEventListenerRegistry.java | P2 |
| T12: Add @Builder to KafkaConnectConfig | KafkaConnectConfig.java | P3 |

---

**Document Version**: 1.0
**Created Date**: 2026-03-19
**Status**: Ready for Implementation
**Dependencies**: Phase 0, 1, 2 completed
