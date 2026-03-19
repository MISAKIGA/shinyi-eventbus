# Architecture Design & Solution - Kafka Optimization

## 1. Current Kafka Implementation Analysis

### 1.1 Existing Components

| Component | Location | Status |
|-----------|----------|--------|
| `KafkaMqEventListenerRegistry` | `registry/KafkaMqEventListenerRegistry.java` | Basic implementation |
| `KafkaConfig` | `config/kafka/KafkaConfig.java` | Configuration holder |
| `KafkaConnectConfig` | `config/kafka/KafkaConnectConfig.java` | Connection properties |
| `KafkaAutoConfiguration` | `config/kafka/KafkaAutoConfiguration.java` | Spring auto-config |

### 1.2 Current Gaps in KafkaMqEventListenerRegistry

```
Current Implementation Issues:
1. No idempotence support (enable.idempotence)
2. No compression type configuration (compression.type=snappy)
3. No security protocol support (PLAINTEXT, SASL_PLAINTEXT, SASL_SSL)
4. No SASL mechanism configuration (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI/Kerberos)
5. No Kerberos support (keytab, principal, krb5.conf)
6. No manual offset commit for exactly-once (enable.auto.commit=false + commitSync)
7. Fixed 1000ms poll timeout, no configurable batching
8. No multi-producer/consumer pool support
9. No performance-optimized defaults (batch.size=16KB, linger.ms=1)
```

### 1.3 Current Gaps in KafkaConnectConfig

| Property | Current Default | Optimized Default | Gap |
|----------|----------------|-------------------|-----|
| `batch.size` | 16384 (16KB) | 65536 (64KB) | Missing optimization |
| `linger.ms` | 1 | 10 | Missing optimization |
| `buffer.memory` | 33554432 (32MB) | 67108864 (64MB) | Missing optimization |
| `max.poll.records` | 500 | 5000-10000 | Too low |
| `fetch.min.bytes` | Not set | 1024-524288 | Missing |
| `fetch.max.wait.ms` | Not set | 1000 | Missing |
| `max.partition.fetch.bytes` | Not set | 10485760 (10MB) | Missing |
| `enable.idempotence` | Not set | false (opt-in) | Missing feature |
| `compression.type` | Not set | snappy | Missing optimization |
| Security properties | None | Multiple protocols | Missing entire subsystem |

---

## 2. Lessons from kafka-demo

### 2.1 Performance Optimization Patterns

#### Producer Optimizations (5x throughput improvement)

```java
// From IdempotentKafkaProducer.java (kafka-demo)
props.put("acks", "all");
props.put("retries", Integer.MAX_VALUE);
props.put("batch.size", 65536);           // 64KB (vs default 16KB)
props.put("linger.ms", 10);               // 10ms (vs default 0)
props.put("buffer.memory", 67108864);     // 64MB (vs default 32MB)
props.put("compression.type", "snappy");  // Snappy compression
props.put("max.in.flight.requests.per.connection", 5);
```

**Benchmark Results (100K messages, 1KB each)**:
- Baseline: 20,000 msg/s
- Optimized: 100,000 msg/s (5x improvement)
- Exactly-once: 100,000 msg/s (no performance penalty)

#### Consumer Optimizations (1.34x throughput improvement)

```java
// From ManualOffsetConsumer.java (kafka-demo)
props.put("enable.auto.commit", false);           // Manual commit for EOS
props.put("max.poll.records", 10000);              // 10K per poll
props.put("fetch.min.bytes", 524288);              // 512KB minimum
props.put("fetch.max.wait.ms", 500);              // Wait up to 500ms
props.put("max.partition.fetch.bytes", 10485760);  // 10MB per partition
```

### 2.2 Authentication Patterns

#### Security Protocol Support Matrix

| Protocol | Encryption | Authentication | Use Case |
|----------|------------|----------------|----------|
| PLAINTEXT | No | No | Dev/Testing |
| SASL_PLAINTEXT | No | Yes (SASL) | Internal networks |
| SASL_SSL | Yes (TLS) | Yes (SASL) | Production |

#### SASL Mechanisms

| Mechanism | Credentials | Security |
|-----------|-------------|----------|
| PLAIN | Username/Password | Low |
| SCRAM-SHA-256 | Username/Salted Password | Medium-High |
| SCRAM-SHA-512 | Username/Salted Password | High |
| GSSAPI (Kerberos) | Keytab/Principal | Highest |

#### Kerberos Implementation Pattern

```java
// From KafkaKerberosTest.java (kafka-demo)
private Properties createProducerProps() {
    Properties props = new Properties();
    props.put("security.protocol", "SASL_PLAINTEXT");
    props.put("sasl.mechanism", "GSSAPI");

    // JAAS Configuration
    String jaasTemplate = "com.sun.security.auth.module.Krb5LoginModule required " +
            "useKeyTab=true keyTab=\"%s\" storeKey=true " +
            "serviceName=\"%s\" principal=\"%s\";";
    props.put("sasl.jaas.config", String.format(jaasTemplate,
            kerberosKeytab, kerberosServiceName, kerberosPrincipal));

    // System properties for Kerberos
    System.setProperty("java.security.auth.login.config", "/etc/kafka/jaas.conf");
    System.setProperty("java.security.krb5.conf", krb5Location);
    return props;
}
```

### 2.3 Exactly-Once Semantics (EOS) Design

#### Producer Side: Idempotent Producer

```java
// From IdempotentKafkaProducer.java (kafka-demo)
// Critical settings for exactly-once:
props.put("enable.idempotence", true);     // Enable idempotence
props.put("acks", "all");                   // All replicas must acknowledge
props.put("retries", Integer.MAX_VALUE);   // Infinite retries
props.put("max.in.flight.requests.per.connection", 5);  // Safe with idempotence
```

#### Consumer Side: Manual Offset Commit

```java
// From ManualOffsetConsumer.java (kafka-demo)
props.put("enable.auto.commit", false);

// After successful message processing:
currentOffsets.put(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset() + 1, "processed")
);

// Batch commit every 100 messages for performance
if (currentOffsets.size() >= 100) {
    consumer.commitSync(offsets);
    currentOffsets.clear();
}
```

### 2.4 Multi-Threading Patterns

#### ProducerPool (Round-Robin Distribution)

```java
// From ProducerPool.java (kafka-demo)
public IdempotentKafkaProducer.PerformanceResult sendDistributed(
        int totalMessages, int messageSize) {
    int messagesPerProducer = totalMessages / poolSize;
    // Each thread gets its own producer for true parallelism
    // Round-robin distributes load evenly
}
```

**Result**: 8.6x improvement over baseline (633ms vs 5,452ms for 100K messages)

---

## 3. Recommended Optimizations for Event Bus

### 3.1 KafkaConnectConfig Enhancements

Add the following properties to `KafkaConnectConfig`:

```java
// Idempotence & EOS
private boolean enableIdempotence = false;
private int maxInFlightRequestsPerConnection = 5;

// Performance optimizations
private String compressionType = "snappy";  // New
private int fetchMinBytes = 1024;             // New (was not set)
private int fetchMaxWaitMs = 1000;            // New (was not set)
private int maxPartitionFetchBytes = 10485760; // New (was not set)

// Security (NEW SUBSYSTEM)
private String securityProtocol = "PLAINTEXT";
private String saslMechanism = "PLAIN";
private String username;
private String password;

// Kerberos (NEW)
private String kerberosServiceName = "kafka";
private String kerberosPrincipal;
private String kerberosKeytab;
private String kerberosKrb5Location;
```

### 3.2 EventBusListener Annotation Enhancement

Add `exactlyOnce` attribute to enable EOS per-listener:

```java
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EventBusListener {
    // ... existing attributes ...

    /**
     * Enable exactly-once semantics for this listener.
     * Requires: enableIdempotence=true on producer side,
     *           manual offset commit on consumer side.
     */
    boolean exactlyOnce() default false;

    /**
     * Manual commit batch size for EOS mode.
     * Commit after every N messages for balancing performance/reliability.
     */
    int commitBatchSize() default 100;
}
```

---

## 4. Exactly-Once Semantics Design for Event Bus

### 4.1 Design Principles

1. **Opt-in**: EOS should be explicitly enabled per listener
2. **No performance penalty when disabled**: Default mode is at-least-once
3. **Idempotent producer**: Enable for exactly-once producer side
4. **Manual offset commit**: Consumer commits only after successful processing
5. **Configurable batching**: Balance between throughput and reliability

### 4.2 Architecture

```
User Configuration
        |
        v
@EventBusListener(exactlyOnce = true)
        |
        v
+-------------------+
| KafkaMqEventListenerRegistry |
+-------------------+
        |
        +---> Producer Config
        |     - enable.idempotence=true
        |     - acks=all
        |     - retries=MAX_VALUE
        |
        +---> Consumer Config
              - enable.auto.commit=false
              - Manual commitSync() after processing
              - Batch commit (every 100 or on shutdown)
```

### 4.3 User Experience

```java
// Simple usage - no changes required
@EventBusListener(name = "kafka", topic = "order.created")
public void onOrderCreated(EventModel<Order> event) {
    // At-least-once delivery (current behavior)
}

// Exactly-once with annotation
@EventBusListener(name = "kafka", topic = "payment.processed",
                  exactlyOnce = true, commitBatchSize = 50)
public void onPaymentProcessed(EventModel<Payment> event) {
    // Exactly-once delivery - no duplicates
}
```

---

## 5. Authentication Strategy (Multi-Auth Support)

### 5.1 Supported Authentication Methods

| Method | Protocol | Configuration Complexity | Security |
|--------|----------|---------------------------|----------|
| None (PLAINTEXT) | PLAINTEXT | None | None |
| SASL/PLAIN | SASL_PLAINTEXT or SASL_SSL | Low | Basic |
| SASL/SCRAM-SHA-256 | SASL_SSL | Low | High |
| SASL/SCRAM-SHA-512 | SASL_SSL | Low | Highest |
| Kerberos/GSSAPI | SASL_PLAINTEXT or SASL_SSL | High | Highest |

### 5.2 Configuration Model

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          is-default: true
          bootstrap-servers: localhost:9092

          # Authentication
          security-protocol: SASL_SSL
          sasl-mechanism: SCRAM-SHA-512
          username: kafka-user
          password: kafka-password

          # OR Kerberos
          # security-protocol: SASL_PLAINTEXT
          # sasl-mechanism: GSSAPI
          # kerberos-service-name: kafka
          # kerberos-principal: kafka/kafka.example.com@EXAMPLE.COM
          # kerberos-keytab: /etc/kafka/kafka.keytab
          # kerberos-krb5-location: /etc/kafka/krb5.conf
```

### 5.3 JAAS Configuration Templates

**PLAIN/SCRAM**:
```java
String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required " +
        "username=\"%s\" password=\"%s\";";
props.put("sasl.jaas.config", String.format(jaasTemplate, username, password));
```

**Kerberos**:
```java
String jaasTemplate = "com.sun.security.auth.module.Krb5LoginModule required " +
        "useKeyTab=true keyTab=\"%s\" storeKey=true " +
        "serviceName=\"%s\" principal=\"%s\";";
props.put("sasl.jaas.config", String.format(jaasTemplate, keytab, serviceName, principal));
```

---

## 6. Performance Optimization Strategies

### 6.1 Producer Performance

| Setting | Default | Optimized | Impact |
|---------|---------|-----------|--------|
| `batch.size` | 16KB | 64KB | Higher batching efficiency |
| `linger.ms` | 0 | 10ms | Better batching, lower CPU |
| `buffer.memory` | 32MB | 64MB | Larger send buffer |
| `compression.type` | none | snappy | 20-30% bandwidth reduction |
| `acks` | 1 | 1 (or all for EOS) | Reliability vs speed |

### 6.2 Consumer Performance

| Setting | Default | Optimized | Impact |
|---------|---------|-----------|--------|
| `max.poll.records` | 500 | 10000 | Fewer round-trips |
| `fetch.min.bytes` | 1 | 512KB | Reduce network calls |
| `fetch.max.wait.ms` | 500ms | 1000ms | Wait for batch fill |
| `max.partition.fetch.bytes` | 1MB | 10MB | Larger per-partition buffer |

### 6.3 Benchmark Results Summary (from kafka-demo)

```
Producer (100K messages, 1KB each):
- Baseline:  20,000 msg/s, 19.53 MB/s
- Optimized: 100,000 msg/s, 97.66 MB/s (5x improvement)
- Multi-threaded: 100,000 msg/s in 633ms (8.6x improvement)
- EOS: 100,000 msg/s (no penalty)

Consumer (100K messages):
- Baseline:  25,059 msg/s
- Optimized: 33,575 msg/s (1.34x improvement)
- EOS: 33,575 msg/s (no penalty)
```

---

## 7. Design Principles

### 7.1 Simplicity
- Default to sensible defaults (not necessarily optimal)
- Opt-in for advanced features (EOS, Kerberos)
- Minimal configuration for common use cases

### 7.2 Elegance
- Consistent API across all MQ types
- Annotation-driven configuration
- Clean separation of concerns

### 7.3 Reliability
- Exactly-once semantics when needed
- Idempotent producer support
- Manual offset commit for guaranteed delivery

### 7.4 Unification
- Single `@EventBusListener` annotation for all MQ types
- Unified `EventModel<T>` for all events
- Consistent error handling and callbacks
