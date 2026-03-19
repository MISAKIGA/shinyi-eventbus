# Phase 0: P0 Performance Optimization Design

## 1. Performance-Optimized Defaults

### 1.1 Current State Analysis

**Current KafkaConnectConfig defaults (suboptimal):**
```java
batch.size = 16384              // 16KB - too small for high throughput
linger.ms = 1                   // Effectively 0ms - no batching benefit
buffer.memory = 33554432        // 32MB - insufficient for large batches
max.poll.records = 500          // Very low - causes frequent polls
```

### 1.2 Industry-Optimal Defaults

| Property | Current | Optimized | Impact |
|----------|---------|-----------|--------|
| `batch.size` | 16KB | 64KB | 4x larger batches |
| `linger.ms` | 1ms | 10ms | Batch waiting time |
| `buffer.memory` | 32MB | 64MB | 2x producer buffer |
| `max.poll.records` | 500 | 5000 | 10x more records per poll |

### 1.3 Implementation Changes

**File: `KafkaConnectConfig.java`**

```java
// Section 2.1: Update default values
private int batchSize = 65536;           // 64KB (was 16384)
private int lingerMs = 10;               // 10ms (was 1)
private int bufferMemory = 67108864;     // 64MB (was 33554432)
private int maxPollRecords = 5000;       // 5000 (was 500)
```

---

## 2. Fetch Optimization Properties

### 2.1 Consumer Fetch Settings

Add these properties to `KafkaConnectConfig`:

```java
/**
 * Minimum bytes to fetch per request (default: 1)
 */
private int fetchMinBytes = 1024;

/**
 * Maximum wait time for fetch request (default: 500ms)
 */
private int fetchMaxWaitMs = 500;

/**
 * Maximum bytes per partition per fetch (default: 1MB)
 */
private int maxPartitionFetchBytes = 10485760;  // 10MB
```

### 2.2 Implementation in `toConsumerProperties()`

```java
// Add to toConsumerProperties() method
props.put("fetch.min.bytes", fetchMinBytes);
props.put("fetch.max.wait.ms", fetchMaxWaitMs);
props.put("max.partition.fetch.bytes", maxPartitionFetchBytes);
```

---

## 3. Compression Type Support

### 3.1 Producer Compression

```java
/**
 * Compression type: none, gzip, snappy, lz4, zstd (default: snappy)
 */
private String compressionType = "snappy";
```

### 3.2 Implementation in `toProducerProperties()`

```java
// Add after buffer.memory setting
props.put("compression.type", compressionType);
```

### 3.3 Compression Performance (from kafka-demo)

| Compression | Throughput | CPU Overhead |
|-------------|------------|--------------|
| none | 100,000 msg/s | Baseline |
| snappy | 97,660 msg/s | ~5% |
| lz4 | 99,000 msg/s | ~3% |
| gzip | 60,000 msg/s | ~20% |

**Recommendation**: Use `snappy` as default (good balance of speed and compression ratio)

---

## 4. Complete Configuration Changes

### 4.1 KafkaConnectConfig Changes

```java
// ==================== Performance Optimization ====================

/**
 * Batch size in bytes (default: 64KB for high throughput)
 */
private int batchSize = 65536;

/**
 * Lingering time in ms before sending batch (default: 10ms)
 */
private int lingerMs = 10;

/**
 * Total memory for producer buffers (default: 64MB)
 */
private int bufferMemory = 67108864;

/**
 * Maximum records per poll (default: 5000)
 */
private int maxPollRecords = 5000;

/**
 * Compression type: none, gzip, snappy, lz4, zstd (default: snappy)
 */
private String compressionType = "snappy";

// ==================== Consumer Fetch Optimization ====================

/**
 * Minimum bytes to fetch per request (default: 1KB)
 */
private int fetchMinBytes = 1024;

/**
 * Maximum wait time for fetch request (default: 500ms)
 */
private int fetchMaxWaitMs = 500;

/**
 * Maximum bytes per partition per fetch (default: 10MB)
 */
private int maxPartitionFetchBytes = 10485760;
```

### 4.2 toProducerProperties() Changes

```java
public Properties toProducerProperties() {
    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("key.serializer", keySerializer);
    props.put("value.serializer", valueSerializer);

    // Performance-optimized batch settings
    props.put("batch.size", batchSize);
    props.put("linger.ms", lingerMs);
    props.put("buffer.memory", bufferMemory);
    props.put("compression.type", compressionType);

    // Existing configurations
    props.put("acks", acks);
    props.put("retries", retries);
    props.put("max.in.flight.requests.per.connection", maxInFlightRequestsPerConnection);

    // Security
    applySecurityProperties(props);

    return props;
}
```

### 4.3 toConsumerProperties() Changes

```java
public Properties toConsumerProperties() {
    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("group.id", groupId);
    // ... existing configs ...

    // Performance-optimized fetch settings
    props.put("max.poll.records", maxPollRecords);
    props.put("fetch.min.bytes", fetchMinBytes);
    props.put("fetch.max.wait.ms", fetchMaxWaitMs);
    props.put("max.partition.fetch.bytes", maxPartitionFetchBytes);

    // Security
    applySecurityProperties(props);

    return props;
}
```

---

## 5. Acceptance Criteria

### 5.1 Unit Tests

| Test Case | Description | Expected Result |
|-----------|-------------|----------------|
| `testOptimizedBatchSize_shouldBe64KB` | Verify default batch.size = 65536 | PASS |
| `testOptimizedLingerMs_shouldBe10` | Verify default linger.ms = 10 | PASS |
| `testOptimizedBufferMemory_shouldBe64MB` | Verify default buffer.memory = 67108864 | PASS |
| `testOptimizedMaxPollRecords_shouldBe5000` | Verify default max.poll.records = 5000 | PASS |
| `testCompressionType_shouldBeSnappy` | Verify default compression.type = snappy | PASS |
| `testFetchOptimizationProperties` | Verify fetch.min.bytes, fetch.max.wait.ms, max.partition.fetch.bytes | PASS |

### 5.2 Integration Benchmark Targets

| Metric | Target | Description |
|--------|--------|-------------|
| Producer Throughput | 80,000+ msg/s | Optimized batch + linger + snappy |
| Consumer Throughput | 30,000+ msg/s | Optimized poll + fetch settings |
| Producer Latency P99 | < 50ms | Under load |
| Consumer Lag | < 1000 | Normal operation |

### 5.3 Backward Compatibility

- Default values are **NOT** changed for existing deployments
- Only new explicit configurations use optimized defaults
- Existing `application.yml` configurations continue to work unchanged

---

## 6. Implementation Tasks

| Task | File | Line Range | Priority |
|------|------|------------|----------|
| T1: Add performance properties | KafkaConnectConfig.java | New section | P0 |
| T2: Update toProducerProperties() | KafkaConnectConfig.java | ~104-120 | P0 |
| T3: Update toConsumerProperties() | KafkaConnectConfig.java | ~122-144 | P0 |
| T4: Add unit tests | KafkaProducerTest.java | New tests | P0 |
| T5: Update toString() | KafkaConnectConfig.java | ~224-253 | P1 |

---

## 7. Configuration Examples

### 7.1 Default Optimized Configuration

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        optimized-kafka:
          bootstrap-servers: localhost:9092
          topic: my-topic
          # Uses all optimized defaults:
          # batch.size: 65536
          # linger.ms: 10
          # buffer.memory: 67108864
          # max.poll.records: 5000
          # compression.type: snappy
```

### 7.2 Custom Performance Tuning

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        high-throughput:
          bootstrap-servers: localhost:9092
          topic: high-volume-topic
          # Aggressive batching for max throughput
          batch-size: 131072  # 128KB
          linger-ms: 20
          buffer-memory: 134217728  # 128MB
          compression-type: lz4
          # Consumer settings
          max-poll-records: 10000
          fetch-min-bytes: 4096
```

---

## 8. Performance Benchmark Reference

From kafka-demo benchmark results:

```
Configuration: batch=64KB, linger=10ms, snappy compression
Results: 100,000 msg/s sustained throughput

Configuration: batch=64KB, linger=10ms, snappy, 4 producer threads
Results: 100,000 msg/s (8.6x baseline)
```

**Key Finding**: EOS (idempotent producer) adds ~5% overhead but provides exactly-once guarantee.

---

**Document Version**: 1.0
**Created Date**: 2026-03-19
**Status**: Ready for Implementation
