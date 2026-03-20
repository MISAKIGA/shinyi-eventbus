# Shinyi EventBus - TODO Iteration Plan

## Project Status

### Completed
- [x] Core Kafka implementation with SASL support
- [x] Kerberos/GSSAPI authentication support (code complete)
- [x] PLAINTEXT, SASL/PLAIN, SASL/SCRAM support
- [x] All 63 unit tests passing (52 original + 11 new)
- [x] Architecture design document
- [x] Performance-optimized configuration reference
- [x] KDC Docker image built and pushed to docker.io/msga/kafka-kdc-krb:latest
- [x] PLAINTEXT Kafka Docker deployment verified (localhost:9092)
- [x] **P0.2: Performance Configuration Integration** - Added compressionType, fetchMinBytes, fetchMaxWaitMs, maxPartitionFetchBytes
- [x] **P0.3: EOS Configuration Properties** - Added enableIdempotence, enableManualCommit, commitBatchSize
- [x] Integration tests with Testcontainers (KafkaEventBusIntegrationTest)
- [x] Benchmark tests (KafkaEventBusBenchmarkTest)
- [x] Fixed key serializer: now uses StringSerializer (compatible with KafkaMqEventListenerRegistry String keys)
- [x] **P0.4: Serialization Performance Optimization** - Fixed JSON mode to only serialize entity (not EventModel wrapper), added RAW mode for high-performance byte-oriented serialization

### In Progress
- [ ] Kerberos Docker deployment verification (blocked by Confluent preflight checks)
- [ ] 10万数据压测基准测试 (10万 message benchmark test)

---

## TODO List

### P0 - Critical (Must Have)

#### P0.1: Kerberos Docker Deployment
- [x] KDC Docker image built: docker.io/msga/kafka-kdc-krb:latest
- [x] Docker-compose files updated to use published image
- [ ] Fix KDC hostname resolution in Docker network
- [ ] Verify Kafka broker starts with GSSAPI authentication
- [ ] Test client connection with Kerberos

**Blocker**: Confluent Kafka Docker image preflight check requires SASL configuration for Zookeeper (both KRaft and Zookeeper modes affected)

#### P0.2: Performance Configuration Integration ✅ COMPLETED
- [x] Add `compressionType` field to `KafkaConnectConfig`
- [x] Add optimized producer defaults from kafka-demo
- [x] Add optimized consumer defaults from kafka-demo

**Reference**:
```yaml
# Optimized Producer
batch.size=65536
linger.ms=10
buffer.memory=67108864
compression.type=snappy

# Optimized Consumer
max.poll.records=5000
fetch.min.bytes=1024
fetch.max.wait.ms=1000
max.partition.fetch.bytes=1048576
```

#### P0.3: EOS Configuration Properties ✅ COMPLETED
- [x] Add `enableIdempotence` to `KafkaConnectConfig`
- [x] Add `enableManualCommit` to `KafkaConnectConfig`
- [x] Add `commitBatchSize` to `KafkaConnectConfig`
- [x] Update `toProducerProperties()` for EOS
- [x] Update `toConsumerProperties()` for EOS

#### P0.4: Serialization Performance Optimization ✅ COMPLETED
- [x] Fix JSON mode: now only serializes entity, not EventModel wrapper
- [x] Add RAW serialize type for high-performance byte-oriented MQ (Kafka, Redis)
- [x] Update SerializeType enum with RAW mode
- [x] Update BaseSerializer to handle RAW mode efficiently

**RAW Mode Benefits**:
- For `byte[]` entity: sends raw bytes directly (no serialization)
- For `String` entity: converts to UTF-8 bytes directly (no JSON overhead)
- For other types: falls back to JSON serialization

**Usage**:
```java
// Use RAW mode for high performance
EventModel.build(topic, event, eventId, async, "RAW", callback);
```

**Changes**:
- `SerializeType.java`: Added RAW("RAW") enum value
- `BaseSerializer.java`:
  - JSON mode now only serializes entity (fixes inconsistency)
  - RAW mode for binary-optimized serialization
- `EventBusListener.java`: Updated documentation to include RAW mode

### P1 - High Priority

#### P1.1: EOS Annotation Implementation
- [ ] Add `exactlyOnce()` attribute to `@EventBusListener`
- [ ] Add `commitBatchSize()` attribute to `@EventBusListener`
- [ ] Update `EventListener` interface with defaults
- [ ] Create `ListenerEosConfig` wrapper class

#### P1.2: Producer Pool
- [ ] Create `KafkaProducerPool` class
- [ ] Add `producerPoolSize` config to `KafkaConnectConfig`
- [ ] Update registry to use pool when configured

#### P1.3: Consumer Pool
- [ ] Create `KafkaConsumerPool` class
- [ ] Add `consumerPoolSize` config to `KafkaConnectConfig`
- [ ] Update registry to use pool when configured

### P2 - Medium Priority

#### P2.1: Latency Tracker
- [ ] Create `LatencyTracker` class
- [ ] Add P50/P90/P95/P99 percentile calculation
- [ ] Integrate into registry for benchmarking

#### P2.2: Dead Letter Queue (DLQ)
- [ ] Create `DeadLetterQueueHandler` class
- [ ] Add `deadLetterTopic` config
- [ ] Add `maxRetries` config
- [ ] Add `retryBackoffMs` config
- [ ] Integrate into consumer loop

#### P2.3: Configuration Builder
- [ ] Add `@Builder` annotation to `KafkaConnectConfig`
- [ ] Create fluent builder API

### P3 - Low Priority (Nice to Have)

#### P3.1: KRaft Mode Support
- [ ] Add KRaft-only configuration options
- [ ] Test Kafka without Zookeeper dependency

#### P3.2: SSL/TLS Support
- [ ] Add SSL configuration properties
- [ ] Test SASL_SSL with SCRAM

---

## Implementation Tasks Detail

### Task P0.1: Kerberos Docker Deployment

**Files to Modify**:
- `docker/kafka-kerberos-kraft.yml` - KRaft mode configuration
- `docker/kafka-kerberos.yml` - Zookeeper mode configuration

**Solution Options**:
1. Use KRaft mode (no Zookeeper) - requires proper cluster ID
2. Configure Zookeeper with SASL/GSSAPI to match Kafka
3. Use existing Kerberos-enabled Kafka cluster

**Current Issue**:
```
java.net.UnknownHostException: kdc: Name or service not known
```

**Next Steps**:
1. Generate valid KRaft cluster ID
2. Ensure KDC hostname is resolvable from Kafka container
3. Test inter-broker Kerberos authentication

---

### Task P0.2: Performance Configuration

**Files to Modify**:
- `KafkaConnectConfig.java`

**New Fields**:
```java
private String compressionType = "snappy";  // Default optimized
private int fetchMinBytes = 1024;           // Consumer
private int fetchMaxWaitMs = 1000;          // Consumer
private int maxPartitionFetchBytes = 1048576; // Consumer
```

---

### Task P0.3: EOS Properties

**Files to Modify**:
- `KafkaConnectConfig.java`

**New Fields**:
```java
private boolean enableIdempotence = false;
private boolean enableManualCommit = false;
private int commitBatchSize = 100;
```

**Producer Changes**:
```java
if (enableIdempotence) {
    props.put("enable.idempotence", true);
    props.put("acks", "all");
    props.put("retries", Integer.MAX_VALUE);
    props.put("max.in.flight.requests.per.connection", 5);
}
```

**Consumer Changes**:
```java
if (enableManualCommit) {
    props.put("enable.auto.commit", false);
}
```

---

## Iteration Timeline

### Iteration 1 (Current)
- Focus: Stabilization and documentation
- Deliverables: Architecture doc, passing tests

### Iteration 2
- Focus: P0.2, P0.3 - Performance and EOS properties
- Deliverables: Optimized Kafka configuration, EOS support

### Iteration 3
- Focus: P1.1, P1.2, P1.3 - Pools and annotations
- Deliverables: Producer/Consumer pools, annotation-based EOS

### Iteration 4
- Focus: P2.1, P2.2 - Latency and DLQ
- Deliverables: Benchmarking, DLQ support

---

## Verification Checklist

### Unit Tests
- [x] All 52 tests passing
- [x] Kerberos configuration covered
- [x] SASL configuration covered
- [ ] EOS configuration covered (new tests needed)

### Integration Tests
- [x] PLAINTEXT Kafka - Working (verified on localhost:9092)
- [ ] SASL/PLAIN Kafka - Needs docker-compose verification
- [ ] SASL/SCRAM Kafka - Needs docker-compose verification
- [ ] Kerberos/GSSAPI Kafka - Blocked (Confluent preflight check complexity)

### Performance Tests
- [ ] Baseline producer benchmark
- [ ] Optimized producer benchmark
- [ ] Multi-threaded producer benchmark
- [ ] EOS producer benchmark

---

## Dependencies

- Docker with docker-compose
- Confluent Kafka 7.5.0 Docker image
- Kerberos utilities (krb5-kdc, krb5-user)
- Running Zookeeper or KRaft-mode Kafka

---

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Docker networking complexity | High | Use docker-compose networking |
| Kerberos configuration | High | Follow kafka-demo patterns |
| KRaft vs Zookeeper | Medium | Support both modes |
| Confluent Docker preflight checks | High | Configure SASL properly or skip with KRaft |

---

**Last Updated**: 2026-03-19
**Status**: Active Development
