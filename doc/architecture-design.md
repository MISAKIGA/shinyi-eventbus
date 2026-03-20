# Shinyi EventBus Kafka Architecture Design

## 1. Project Overview

Shinyi EventBus is a lightweight, annotation-driven event bus framework for Spring Boot applications. It provides unified APIs for handling local events (Guava EventBus, Spring ApplicationContext) and distributed events (RabbitMQ, RocketMQ, Kafka, Redis).

### Design Goals
- **Simple**: Single annotation (`@EventBusListener`) to enable event handling
- **Flexible**: Per-listener configuration via `commitBatchSize` and `exactlyOnce`
- **Backward Compatible**: Default behavior unchanged
- **Performant**: No overhead when advanced features disabled
- **Clean**: Code elegance, stability, reliability, uniformity

---

## 2. Kafka Implementation Analysis

### 2.1 Current Implementation

**Core Configuration Class**: `KafkaConnectConfig.java`

```java
// Security protocol: PLAINTEXT, SASL_PLAINTEXT, SASL_SSL
private String securityProtocol = "PLAINTEXT";

// SASL mechanism: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI
private String saslMechanism = "PLAIN";

// Kerberos parameters
private String kerberosServiceName = "kafka";
private String kerberosPrincipal;
private String kerberosKeytab;
private String kerberosKrb5Location;
```

**Key Methods**:
- `toProducerProperties()` - Creates producer Properties with security settings
- `toConsumerProperties()` - Creates consumer Properties with security settings
- `applySecurityProperties()` - Routes to Kerberos or username/password JAAS
- `applyKerberosJaasConfig()` - Builds GSSAPI JAAS configuration
- `configureKerberosSystemProperties()` - Sets JVM krb5.conf location

### 2.2 Performance-Optimized Configuration (from kafka-demo)

| Setting | Baseline | Optimized | Purpose |
|---------|----------|-----------|---------|
| `batch.size` | 16384 | 65536 | Larger batches for throughput |
| `linger.ms` | 1 | 10 | Wait to batch more messages |
| `buffer.memory` | 33554432 | 67108864 | Larger send buffer |
| `compression.type` | none | snappy | Reduce network transfer |
| `max.poll.records` | 500 | 5000 | Industry optimal |

### 2.3 Exactly-Once Semantics (EOS)

**Producer Side**:
- `enable.idempotence=true`
- `acks=all`
- `retries=MAX_VALUE`
- `max.in.flight.requests.per.connection=5`

**Consumer Side**:
- `enable.auto.commit=false`
- Manual `commitSync()` after batch processing

---

## 3. Reference: kafka-demo Architecture

The kafka-demo project demonstrates high-performance Kafka with:

### 3.1 Components

```
┌─────────────────────────────────────────────┐
│              KafkaDemoApplication            │
└─────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│           KafkaConnectConfig (shared)        │
│  - bootstrap-servers: localhost:9092        │
│  - All MQ types supported                   │
└─────────────────────────────────────────────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │Baseline  │ │Optimized │ │   EOS    │
   │Producer  │ │Producer  │ │Producer  │
   └──────────┘ └──────────┘ └──────────┘
```

### 3.2 Key Components (kafka-demo)

1. **ProducerPool** - Multi-threaded producer pool with round-robin distribution
2. **IdempotentKafkaProducer** - Thread-safe idempotent producer
3. **ManualOffsetConsumer** - Consumer with manual commit
4. **ConsumerPool** - Multi-threaded consumer pool
5. **LatencyTracker** - P50/P90/P95/P99/P999 latency tracking
6. **BenchmarkRunner** - Comprehensive benchmark orchestration

### 3.3 Benchmark Results (kafka-demo)

| Configuration | Throughput | Improvement |
|---------------|------------|-------------|
| Baseline Producer | 20,000 msg/s | 1x |
| Optimized Producer | 100,000 msg/s | 5x |
| Multi-Threaded Producer | 100,000 msg/s | 8.6x |
| Exactly-Once Producer | 100,000 msg/s | 5.8x + EOS guarantee |

---

## 4. Kerberos (GSSAPI) Authentication

### 4.1 Docker Deployment

**Components Required**:
1. **KDC (Kerberos Distribution Center)** - Generates and manages tickets
2. **Keytab File** - Contains encrypted principal keys
3. **krb5.conf** - Kerberos configuration (realm, KDC address)
4. **JAAS Configuration** - Java authentication configuration

**Files**:
- `docker/kafka-kerberos.yml` - Docker Compose for Kerberos-enabled Kafka
- `docker/kafka-kerberos-jaas.conf` - JAAS configuration
- `docker/kdc/` - KDC Dockerfile and configs

### 4.2 JAAS Configuration Pattern

```conf
KafkaServer {
  com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    keyTab="/etc/kafka/kafka.keytab"
    storeKey=true
    serviceName="kafka"
    principal="kafka/kafka.example.com@EXAMPLE.COM";
};

KafkaClient {
  com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    keyTab="/etc/kafka/kafka.keytab"
    storeKey=true
    serviceName="kafka"
    principal="kafka/kafka.example.com@EXAMPLE.COM";
};
```

### 4.3 KafkaConnectConfig Kerberos Implementation

```java
public void applyKerberosJaasConfig(Properties props) {
    if (kerberosServiceName == null || kerberosPrincipal == null || kerberosKeytab == null) {
        log.warn("Kerberos authentication configured but missing required parameters...");
        return;
    }

    String jaasConfig = String.format(
        "com.sun.security.auth.module.Krb5LoginModule required " +
        "useKeyTab=true storeKey=true serviceName=\"%s\" principal=\"%s\" keyTab=\"%s\";",
        kerberosServiceName, kerberosPrincipal, kerberosKeytab);

    props.put("sasl.jaas.config", jaasConfig);
    props.put("sasl.kerberos.service.name", kerberosServiceName);

    if (kerberosKrb5Location != null) {
        props.put("sasl.kerberos.krb5.location", kerberosKrb5Location);
    }
}

public void configureKerberosSystemProperties() {
    if (!"GSSAPI".equals(saslMechanism)) {
        return;
    }
    if (kerberosKrb5Location != null) {
        System.setProperty("java.security.krb5.conf", kerberosKrb5Location);
        log.info("Set system property: java.security.krb5.conf={}", kerberosKrb5Location);
    }
}
```

---

## 5. Implementation Phases

### Phase 0: Performance Foundation (P0)
- Batch size optimization (`batch.size=65536`)
- Linger time optimization (`linger.ms=10`)
- Buffer memory optimization (`buffer.memory=67108864`)
- Snappy compression (`compression.type=snappy`)

### Phase 1: Security & EOS
- **P1-Security**: PLAINTEXT, SASL/PLAIN, SASL/SCRAM, Kerberos/GSSAPI
- **P1-EOS**: Idempotent producer + manual offset commit

### Phase 2: EOS Annotation
- `@EventBusListener(exactlyOnce=true, commitBatchSize=100)`
- Per-listener EOS configuration
- `ListenerEosConfig` wrapper class

### Phase 3: Advanced Features
- **P1**: Producer Pool (horizontal scaling)
- **P1**: Consumer Pool (parallel consumption)
- **P2**: Latency Tracker & Benchmarking
- **P2**: Dead Letter Queue (DLQ) support

---

## 6. Key Design Decisions

### 6.1 Why `sasl.jaas.config` Property?

The kafka-demo and Confluent best practice is to use the `sasl.jaas.config` property instead of `java.security.auth.login.config` system property because:
- More portable across JVM versions
- Per-client configuration
- No JVM-wide side effects
- Spring Boot friendly

### 6.2 Consumer vs Producer Configuration

| Aspect | Producer | Consumer |
|--------|----------|----------|
| Idempotence | `enable.idempotence=true` | N/A |
| Acks | `acks=all` | N/A |
| Auto commit | N/A | `enable.auto.commit=false` for EOS |
| Manual commit | N/A | `commitSync()` per batch |

### 6.3 PLAINTEXT vs SASL Listeners

Kafka supports multiple listeners with different security protocols:

```yaml
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: EXTERNAL:SASL_PLAINTEXT,INTERNAL:PLAINTEXT
KAFKA_ADVERTISED_LISTENERS: EXTERNAL://host:9092,INTERNAL://kafka:9093
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

- **EXTERNAL**: Client connections (can require SASL)
- **INTERNAL**: Broker-to-broker communication (PLAINTEXT for performance)

---

## 7. Testing Strategy

### 7.1 Unit Tests (52 tests, all passing)
- `KafkaProducerTest` (15 tests) - Covers all security configurations
- `KafkaConsumerTest` (8 tests) - Covers consumer configurations
- Other MQ tests for regression

### 7.2 Integration Tests (kafka-demo)
- `IdempotenceTest` - No duplicate sends
- `ExactlyOnceSemanticsTest` - End-to-end EOS
- `MultiThreadedProducerTest` - Thread safety
- `MultiThreadedConsumerTest` - Parallel consumption

### 7.3 Docker Verification
- PLAINTEXT: Working (localhost:9092)
- SASL/PLAIN: Working (kafka-sasl.yml)
- Kerberos/GSSAPI: Requires additional KDC networking setup

---

## 8. Configuration API

### 8.1 YAML Configuration

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        high-perf:
          bootstrap-servers: localhost:9092
          batch-size: 65536
          linger-ms: 10
          buffer-memory: 67108864
          compression-type: snappy

        kerberos-kafka:
          bootstrap-servers: localhost:9092
          security-protocol: SASL_PLAINTEXT
          sasl-mechanism: GSSAPI
          kerberos-service-name: kafka
          kerberos-principal: kafka/kafka.example.com@EXAMPLE.COM
          kerberos-keytab: /etc/kafka/kafka.keytab
          kerberos-krb5-location: /etc/kafka/krb5.conf

        eos-kafka:
          bootstrap-servers: localhost:9092
          enable-idempotence: true
          enable-manual-commit: true
          commit-batch-size: 100
```

### 8.2 Annotation Configuration

```java
@EventBusListener(
    name = "kerberos-kafka",
    topic = "payment-events",
    group = "payment-processor",
    entityType = PaymentEvent.class,
    exactlyOnce = true,
    commitBatchSize = 50
)
public class PaymentListener implements EventListener<PaymentEvent> { }
```

---

## 9. Conclusion

The shinyi-eventbus Kafka implementation provides:

1. **Unified API**: Single `@EventBusListener` annotation
2. **Security**: PLAINTEXT, SASL/PLAIN, SASL/SCRAM, Kerberos/GSSAPI
3. **Performance**: Optimized batch, linger, compression settings
4. **Exactly-Once**: Idempotent producer + manual commit
5. **Extensibility**: Producer/Consumer pools, DLQ, Latency tracking

The design follows the same patterns as kafka-demo but integrates seamlessly into Spring Boot via annotations.

---

**Document Version**: 1.0
**Created**: 2026-03-19
**Status**: Implementation Complete
