# TODO Iteration Plan - Kafka Optimization

## Phase 1: Quick Wins (Low Complexity, High Impact)

### 1.1 Add Performance-Optimized Defaults
**Description**: Update `KafkaConnectConfig` with industry-optimal default values

**Current values**:
```java
batch.size = 16384        // 16KB
linger.ms = 1            // 0ms effectively
buffer.memory = 33554432 // 32MB
max.poll.records = 500   // Very low
```

**New values**:
```java
batch.size = 65536        // 64KB
linger.ms = 10           // 10ms
buffer.memory = 67108864 // 64MB
max.poll.records = 5000  // 10x increase
```

**Estimated complexity**: Low (property changes only)

**Files affected**:
- `KafkaConnectConfig.java`

---

### 1.2 Add Fetch Optimization Properties
**Description**: Add missing consumer fetch settings for high throughput

**Add properties**:
```java
private int fetchMinBytes = 1024;              // Default 1KB
private int fetchMaxWaitMs = 1000;             // Default 1s
private int maxPartitionFetchBytes = 10485760; // Default 10MB
```

**Estimated complexity**: Low (property additions)

**Files affected**:
- `KafkaConnectConfig.java`
- `toConsumerProperties()` method

---

### 1.3 Add Compression Type Support
**Description**: Add `compression.type` configuration for bandwidth optimization

**Add property**:
```java
private String compressionType = "snappy";  // Default snappy
```

**Estimated complexity**: Low (property + apply to producer)

**Files affected**:
- `KafkaConnectConfig.java`
- `toProducerProperties()` method

---

## Phase 2: Security & Authentication (Medium Complexity)

### 2.1 Add SASL/PLAIN Authentication
**Description**: Support username/password authentication via SASL PLAIN

**Add properties**:
```java
private String securityProtocol = "PLAINTEXT";
private String saslMechanism = "PLAIN";
private String username;
private String password;
```

**Implementation in `KafkaConnectConfig.toProducerProperties()`**:
```java
if ("SASL_PLAINTEXT".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol)) {
    props.put("security.protocol", securityProtocol);
    props.put("sasl.mechanism", saslMechanism);

    if ("PLAIN".equals(saslMechanism)) {
        String jaasTemplate = "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                "username=\"%s\" password=\"%s\";";
        props.put("sasl.jaas.config", String.format(jaasTemplate, username, password));
    }
}
```

**Estimated complexity**: Medium

**Files affected**:
- `KafkaConnectConfig.java`
- `KafkaMqEventListenerRegistry.java`

---

### 2.2 Add SCRAM-SHA-256/512 Support
**Description**: Extend SASL support to SCRAM mechanisms

**Changes**: Same as SASL/PLAIN but different JAAS template:
```java
if ("SCRAM-SHA-256".equals(saslMechanism) || "SCRAM-SHA-512".equals(saslMechanism)) {
    String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"%s\" password=\"%s\";";
    props.put("sasl.jaas.config", String.format(jaasTemplate, username, password));
}
```

**Estimated complexity**: Low (extension of 2.1)

---

### 2.3 Add Kerberos/GSSAPI Support
**Description**: Support enterprise Kerberos authentication

**Add properties**:
```java
private String kerberosServiceName = "kafka";
private String kerberosPrincipal;
private String kerberosKeytab;
private String kerberosKrb5Location;
```

**JAAS Configuration**:
```java
if ("GSSAPI".equals(saslMechanism)) {
    String jaasTemplate = "com.sun.security.auth.module.Krb5LoginModule required " +
            "useKeyTab=true keyTab=\"%s\" storeKey=true " +
            "serviceName=\"%s\" principal=\"%s\";";
    props.put("sasl.jaas.config", String.format(jaasTemplate,
            kerberosKeytab, kerberosServiceName, kerberosPrincipal));

    // System properties for Kerberos
    if (kerberosKrb5Location != null) {
        System.setProperty("java.security.krb5.conf", kerberosKrb5Location);
    }
}
```

**Estimated complexity**: Medium

**Files affected**:
- `KafkaConnectConfig.java`
- `KafkaMqEventListenerRegistry.java` (init method)
- `KafkaAutoConfiguration.java`

---

## Phase 3: Exactly-Once Semantics (Medium-High Complexity)

### 3.1 Add Idempotence Configuration
**Description**: Add `enable.idempotence` support for exactly-once producer

**Add properties**:
```java
private boolean enableIdempotence = false;
private int maxInFlightRequestsPerConnection = 5;
```

**Apply in producer**:
```java
if (enableIdempotence) {
    props.put("enable.idempotence", true);
    props.put("acks", "all");
    props.put("retries", Integer.MAX_VALUE);
    props.put("max.in.flight.requests.per.connection", 5);
}
```

**Estimated complexity**: Medium

---

### 3.2 Add Manual Offset Commit
**Description**: Add `enable.auto.commit=false` with manual commitSync

**Consumer changes**:
```java
if (exactlyOnceMode) {
    consumerProps.put("enable.auto.commit", false);
}
```

**Offset tracking and commit**:
```java
Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();

// After successful processing:
currentOffsets.put(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset() + 1, "processed")
);

// Batch commit
if (currentOffsets.size() >= commitBatchSize) {
    consumer.commitSync(currentOffsets);
    currentOffsets.clear();
}
```

**Estimated complexity**: Medium-High

---

### 3.3 Add Exactly-Once to EventBusListener Annotation
**Description**: Add `exactlyOnce` and `commitBatchSize` to annotation

**Annotation changes**:
```java
/**
 * Enable exactly-once semantics for this listener.
 */
boolean exactlyOnce() default false;

/**
 * Commit batch size for manual offset commit mode.
 */
int commitBatchSize() default 100;
```

**Registry changes**: Read annotation values and configure consumer accordingly

**Estimated complexity**: Medium

---

## Phase 4: Advanced Features (High Complexity)

### 4.1 Producer Pool for High Throughput
**Description**: Add multi-producer pool support for parallel message production

**Implementation pattern** (from kafka-demo):
```java
public class KafkaProducerPool {
    private final List<KafkaProducer<String, byte[]>> producers;
    private final AtomicLong roundRobinCounter;

    public KafkaProducer<String, byte[]> getNextProducer() {
        int index = (int) (roundRobinCounter.getAndIncrement() % producers.size());
        return producers.get(index);
    }
}
```

**Configuration**:
```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          producer-pool-size: 4
```

**Estimated complexity**: High (new class + configuration)

---

### 4.2 Consumer Pool for Parallel Consumption
**Description**: Add multi-consumer pool for parallel consumption from partitions

**Implementation pattern**:
```java
public class KafkaConsumerPool {
    private final List<KafkaConsumer<String, byte[]>> consumers;

    // Each consumer needs different group.id for independent offset tracking
    private String getGroupIdForIndex(int index) {
        return baseGroupId + "-" + index;
    }
}
```

**Estimated complexity**: High

---

### 4.3 Latency Tracker & Benchmarking
**Description**: Add P50/P90/P95/P99/P999 latency tracking

**Implementation**:
```java
public class LatencyTracker {
    private final LongAdder[] latencyBuckets;

    public void recordLatency(long latencyMs) {
        int bucket = getBucket(latencyMs);
        latencyBuckets[bucket].increment();
    }

    public LatencyStats getStats() {
        // Calculate percentiles
    }
}
```

**Estimated complexity**: Medium

---

## Priority Matrix

| Item | Impact | Complexity | Priority |
|------|--------|------------|----------|
| 1.1 Performance defaults | High | Low | P0 |
| 1.2 Fetch optimization | High | Low | P0 |
| 1.3 Compression support | High | Low | P0 |
| 2.1 SASL/PLAIN | Medium | Medium | P1 |
| 2.2 SCRAM support | Medium | Low | P1 |
| 2.3 Kerberos | High | Medium | P1 |
| 3.1 Idempotence | High | Medium | P1 |
| 3.2 Manual offset commit | High | Medium-High | P1 |
| 3.3 Annotation EOS | High | Medium | P2 |
| 4.1 Producer pool | Medium | High | P2 |
| 4.2 Consumer pool | Medium | High | P2 |
| 4.3 Benchmarking | Low | Medium | P3 |

---

## Summary

The kafka-demo provides a comprehensive, battle-tested implementation of:
1. **5x producer throughput optimization** via batch/linger/compression tuning
2. **Multi-auth support** (PLAIN, SCRAM, Kerberos)
3. **Exactly-once semantics** with no performance penalty
4. **Multi-threaded producer/consumer pools** for horizontal scaling

The current shinyi-eventbus Kafka implementation is basic but well-structured. The recommended improvements follow a logical progression:
- **Phase 1**: Quick wins with performance defaults (low risk, high reward)
- **Phase 2**: Security features for enterprise adoption
- **Phase 3**: Exactly-once semantics for reliability-critical applications
- **Phase 4**: Advanced features for maximum throughput scenarios

Each phase builds on the previous, ensuring incremental value delivery and manageable complexity.
