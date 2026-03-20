# Phase 1 Exactly-Once Semantics (EOS) 核心设计文档

## 1. EOS 原理详解

### 1.1 消息传递语义对比

| 语义 | 描述 | 重复可能性 | 丢失可能性 | 适用场景 |
|------|------|-----------|-----------|---------|
| **At-most-once** | 最多传递一次 | 无 | 有 | 日志收集、指标上报 |
| **At-least-once** | 至少传递一次 | 有 | 无 | 大多数业务场景 |
| **Exactly-once** | 精确传递一次 | 无 | 无 | 支付、订单、库存等关键业务 |

### 1.2 Kafka EOS 实现原理

Kafka 的 Exactly-Once 语义通过以下机制实现:

#### 1.2.1 Producer 端 - 幂等 Producer (Idempotent Producer)

Kafka 使用 **PID (Producer ID) + Sequence Number** 实现幂等性:

```
+----------------------------------------------------------+
|                    Broker 端去重机制                       |
+----------------------------------------------------------+
|  每个 Producer 分配唯一 PID (max 2^32)                    |
|  每次发送消息递增 Sequence Number (0 ~ 2^31)              |
|                                                           |
|  消息去重条件:                                             |
|    PID 相同 + Sequence Number 相同 = 重复消息             |
|                                                           |
|  优势:                                                    |
|    - Broker 自动去重，无需业务方处理                       |
|    - 无额外存储开销                                       |
|    - 性能损耗极小 (<5%)                                   |
+----------------------------------------------------------+
```

**关键配置参数:**

```java
enable.idempotence = true      // 启用幂等性
acks = "all"                   // 所有副本确认（必须）
retries = MAX_VALUE            // 最大重试次数（必须）
max.in.flight.requests.per.connection = 5  // 5 是幂等安全值
```

#### 1.2.2 Consumer 端 - 手动 Offset 提交

Consumer 通过手动提交 Offset 实现 Exactly-Once:

```java
// 1. 禁用自动提交
enable.auto.commit = false

// 2. 处理消息成功后手动提交
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
offsets.put(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset() + 1, "processed")
);
consumer.commitSync(offsets);

// 3. offset + 1 的含义
//    表示"已处理完 offset 之前的消息，下一次从 offset + 1 开始消费"
```

### 1.3 性能影响分析

**kafka-demo 基准测试结果 (100K 消息, 1KB/消息):**

#### Producer 性能对比

| 配置 | 吞吐量 | 带宽 | 时长 | 提升倍数 | Exactly-Once |
|------|--------|------|------|----------|--------------|
| Baseline (batch=16KB, linger=1ms) | 20,000 msg/s | 19.53 MB/s | 5,452 ms | 1x | ❌ |
| Optimized (batch=64KB, linger=10ms, snappy) | 100,000 msg/s | 97.66 MB/s | 1,383 ms | **5x** | ❌ |
| Multi-Threaded (4 threads) | 100,000 msg/s | 97.66 MB/s | 633 ms | **8.6x** | ❌ |
| Exactly-Once (idempotent) | 100,000 msg/s | 97.66 MB/s | 938 ms | **5.8x** | ✅ |

#### Consumer 性能对比

| 配置 | 吞吐量 | 带宽 | 时长 | 提升倍数 | Exactly-Once |
|------|--------|------|------|----------|--------------|
| Baseline (poll=500, fetch.min=1B) | 25,059 msg/s | 24.47 MB/s | 4,721 ms | 1x | ❌ |
| Optimized (poll=10000, fetch.min=512KB) | 33,575 msg/s | 32.79 MB/s | 3,718 ms | **1.34x** | ❌ |
| Exactly-Once (manual commitSync) | 33,575 msg/s | 32.79 MB/s | 3,825 ms | **1.34x** | ✅ |

**关键发现:**
- **EOS 无性能惩罚**: Exactly-Once 配置与优化配置性能几乎相同
- **Consumer 瓶颈**: Consumer 约为 Producer 速度的 1/3（单分区限制）

---

## 2. Producer 幂等设计

### 2.1 KafkaConnectConfig 属性变更

**当前状态 (缺少 EOS 支持):**

```java
// src/main/java/com/shinyi/eventbus/config/kafka/KafkaConnectConfig.java
private String acks = "1";           // 不支持 "all"
private int retries = 3;            // 默认 3，不支持 MAX_VALUE
// 缺少 enableIdempotence 属性
// 缺少 maxInFlightRequestsPerConnection 属性
```

**Phase 1 设计方案:**

```java
// ========== 新增属性 ==========

/**
 * 启用幂等 Producer
 * true: enable.idempotence=true, acks=all, retries=MAX_VALUE
 * false: 使用用户配置的 acks 和 retries
 */
private boolean enableIdempotence = false;

/**
 * 最大飞行中的请求数
 * 幂等模式下必须 <= 5
 */
private int maxInFlightRequestsPerConnection = 5;

/**
 * 批量提交大小 (Consumer 手动提交)
 */
private int commitBatchSize = 100;

/**
 * 启用手动 Offset 提交 (Consumer EOS)
 */
private boolean enableManualCommit = false;
```

### 2.2 toProducerProperties() 改动

**当前实现:**

```java
public Properties toProducerProperties() {
    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("acks", acks);
    props.put("retries", retries);
    props.put("batch.size", batchSize);
    props.put("linger.ms", lingerMs);
    props.put("buffer.memory", bufferMemory);
    props.put("max.in.flight.requests.per.connection", maxInFlightRequestsPerConnection);
    props.put("key.serializer", keySerializer);
    props.put("value.serializer", valueSerializer);
    return props;
}
```

**Phase 1 改动方案:**

```java
public Properties toProducerProperties() {
    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("key.serializer", keySerializer);
    props.put("value.serializer", valueSerializer);

    // 基础配置
    props.put("batch.size", batchSize);
    props.put("linger.ms", lingerMs);
    props.put("buffer.memory", bufferMemory);

    // 幂等性配置 (优先级最高)
    if (enableIdempotence) {
        // 强制使用幂等模式的安全配置
        props.put("enable.idempotence", true);
        props.put("acks", "all");
        props.put("retries", Integer.MAX_VALUE);
        props.put("max.in.flight.requests.per.connection", 5);
        log.info("EOS: Idempotent producer enabled - acks=all, retries=MAX, max.in.flight=5");
    } else {
        // 使用用户配置
        props.put("acks", acks);
        props.put("retries", retries);
        props.put("max.in.flight.requests.per.connection", maxInFlightRequestsPerConnection);
    }

    return props;
}
```

### 2.3 条件启用逻辑

**设计原则:**
1. **Opt-in 模式**: 默认 `enableIdempotence=false`，不强制改变现有行为
2. **自动设置安全值**: 启用幂等时，强制设置 `acks=all` 和 `retries=MAX_VALUE`
3. **向后兼容**: 未设置 `enableIdempotence` 时，使用现有 `acks` 和 `retries` 配置

**配置示例:**

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          # 启用 EOS
          enable-idempotence: true
          # commit batch size for manual offset commit
          commit-batch-size: 100
          # 手动提交模式
          enable-manual-commit: true
```

---

## 3. Consumer 手动提交设计

### 3.1 当前问题分析

**KafkaMqEventListenerRegistry.java 第 73-118 行:**

```java
private void initConsumer(com.shinyi.eventbus.EventListener<T> listener) {
    Properties consumerProps = kafkaConnectConfig.toConsumerProperties();
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, listener.group());

    KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
    // ...

    executor.submit(() -> {
        while (!Thread.currentThread().isInterrupted()) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                try {
                    // 处理消息 - 无手动提交
                    finalListener.onMessage((T) eventModel);
                } catch (Exception e) {
                    log.warn("Message processing failed: " + e.getMessage(), e);
                }
            }
            // 无 commitSync 调用
        }
    });
}
```

**问题:**
1. `enableAutoCommit=true` (默认) - 自动提交可能导致重复消费
2. 无手动 Offset 提交机制
3. 无处理失败补偿机制

### 3.2 Offset 追踪数据结构

```java
/**
 * Per-Partition Offset 追踪
 *
 * 使用 Map<TopicPartition, OffsetAndMetadata> 存储待提交的 Offset
 * - TopicPartition: Topic + Partition 唯一标识
 * - OffsetAndMetadata: 要提交的 Offset 值 + 元数据
 *
 * 关键: offset + 1 表示"已处理完当前消息，下一次从下一条开始消费"
 */
Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new HashMap<>();

// 提交条件
if (pendingOffsets.size() >= commitBatchSize) {
    commitOffsets(pendingOffsets);
    pendingOffsets.clear();
}
```

### 3.3 批量提交策略

**设计原则:**
1. **批量提交**: 不是每条消息提交一次，而是累积一定量后批量提交
2. **按分区提交**: Kafka 按分区粒度提交，不同分区互不影响
3. **失败重试**: 提交失败时记录日志并重试

### 3.4 失败重试和补偿机制

**Phase 1 设计:**

```java
/**
 * 消息处理结果回调
 */
public interface ProcessCallback {
    void onSuccess(ConsumerRecord<?, ?> record);
    void onFailure(ConsumerRecord<?, ?> record, Exception e);
}

/**
 * 失败补偿策略:
 * 1. 记录失败消息到重试队列
 * 2. 继续处理后续消息
 * 3. 最终 shutdown 时确保已提交的 offset 是安全的
 */
private void handleProcessingFailure(ConsumerRecord<?, ?> record, Exception e) {
    // 记录到 Dead Letter Queue (可选)
    log.error("Message processing failed permanently: topic={}, partition={}, offset={}, error={}",
              record.topic(), record.partition(), record.offset(), e.getMessage());

    // 不阻塞后续消息处理
    // 失败的 offset 不会被提交，下次重平衡后会重新消费
}
```

---

## 4. 验收标准

### 4.1 Producer 幂等测试

**验收条件:**
- [ ] 发送 1000 条消息，broker 返回成功 1000 条
- [ ] 消费端收到正好 1000 条消息
- [ ] 所有消息 key 唯一，无重复

### 4.2 Consumer 手动提交测试

**验收条件:**
- [ ] 第一次消费完成，所有消息成功处理
- [ ] 第二次消费得到 0 条消息（因为 offset 已提交）
- [ ] 无消息重复消费

### 4.3 EOS 端到端测试

**验收条件:**
- [ ] 100K 消息端到端测试 0 重复
- [ ] 吞吐量达到 30,000+ msg/s (Consumer)
- [ ] Producer 吞吐量达到 80,000+ msg/s

---

## 5. 实现任务清单

### P1.4 Idempotence 配置

- [ ] **T1**: `KafkaConnectConfig` 添加 `enableIdempotence` 属性
- [ ] **T2**: 修改 `toProducerProperties()` 支持幂等配置
- [ ] **T3**: 添加单元测试
- [ ] **T4**: 更新配置文档

### P1.5 手动 Offset 提交

- [ ] **T5**: `KafkaConnectConfig` 添加 `enableManualCommit`, `commitBatchSize` 属性
- [ ] **T6**: 修改 `KafkaMqEventListenerRegistry` 支持手动提交
- [ ] **T7**: 添加 Offset 追踪和批量提交逻辑
- [ ] **T8**: 实现 shutdown 时 offset 保护
- [ ] **T9**: 添加集成测试
- [ ] **T10**: 更新配置文档

---

## 6. 参考资料

- **设计文档**: `doc/kafka-optimization-architecture.md`
- **参考实现**: `/root/.openclaw/workspace-ceo/shinyi-demo/kafka-demo/`
- **当前代码**: `/root/.openclaw/workspace-ceo/shinyi-eventbus/src/main/java/com/shinyi/eventbus/`

**文档版本**: 1.0
**创建日期**: 2026-03-19
**状态**: 待评审
