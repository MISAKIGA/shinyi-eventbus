# Kafka EventBus 性能基准测试文档

**版本**: 1.1.3
**日期**: 2026-03-21
**测试环境**: Testcontainers (confluentinc/cp-kafka:7.5.0)

---

## 测试结果汇总

### Producer 性能 (EventBus API, 10K 消息)

| 测试 | 吞吐量 | 带宽 | 模式 | 备注 |
|------|--------|------|------|------|
| **Async+Batch** | **56,497 msg/s** | 55.17 MB/s | ASYNC | 🏆 最佳 |
| PeriodicFlush | 50,761 msg/s | 49.57 MB/s | ASYNC | |
| E2E-EOS-Producer | 47,846 msg/s | 46.73 MB/s | ASYNC | EOS 语义 |
| E2E-NonEOS-Producer | 33,783 msg/s | 32.99 MB/s | ASYNC | |
| EOS-Producer | 24,937 msg/s | 24.35 MB/s | ASYNC | idempotent=true |
| HighThroughput | 18,083 msg/s | 17.66 MB/s | ASYNC | compression=snappy |
| MultiThreaded-Producer | 8,798 msg/s | 8.59 MB/s | ASYNC | 4 threads |
| Sync+NoBatch | 737 msg/s | 0.72 MB/s | SYNC | 每条同步发送 |

### Consumer 性能 (直接 KafkaConsumer)

| 测试 | 吞吐量 | 带宽 | 备注 |
|------|--------|------|------|
| HighThroughput-Consumer | 100,120 msg/s | 137.79 MB/s | 大 fetch |
| ManualCommit-Consumer | 82,968 msg/s | 114.20 MB/s | 手动提交 |
| AutoCommit-Consumer | 77,395 msg/s | 106.53 MB/s | 自动提交 |
| E2E-EOS-Consumer | 71,524 msg/s | 98.45 MB/s | EOS 消费 |
| E2E-NonEOS-Consumer | 11,139 msg/s | 15.33 MB/s | |

### EOS 开销分析

| 模式 | Producer 吞吐 | Consumer 吞吐 | EOS 开销 |
|------|---------------|---------------|----------|
| Non-EOS | 33,783 msg/s | 11,139 msg/s | - |
| EOS | 24,937 msg/s | 71,524 msg/s | Producer: ~27%, Consumer: +540% |

---

## 配置方法

### 1. 高吞吐量 Producer 配置 (推荐)

```java
KafkaConnectConfig config = new KafkaConnectConfig();
config.setBootstrapServers("localhost:9092");
config.setTopic("your-topic");
config.setGroupId("your-group");

// 核心优化配置
config.setAcks("all");                    // 等待所有 ISR 确认
config.setBatchSize(65536);               // 64KB batch
config.setLingerMs(10);                   // 10ms linger 促进 batching
config.setBufferMemory(67108864);         // 64MB buffer
config.setCompressionType("snappy");      // Snappy 压缩
config.setAutoFlush(false);               // 依赖 Kafka 内部 batching
config.setFlushInterval(Integer.MAX_VALUE); // 不主动 flush
```

### 2. Exactly-Once 语义配置

```java
KafkaConnectConfig config = new KafkaConnectConfig();
config.setBootstrapServers("localhost:9092");
config.setTopic("your-topic");
config.setGroupId("your-group");

// Producer EOS 配置
config.setEnableIdempotence(true);        // 启用幂等 producer
config.setAcks("all");                    // 配合 idempotence
config.setRetries(Integer.MAX_VALUE);     // Kafka 推荐值

// Consumer EOS 配置
config.setEnableAutoCommit(false);         // 禁用自动提交
config.setEnableManualCommit(true);        // 启用手动提交
config.setCommitBatchSize(100);            // 每 100 条提交一次
```

### 3. 快速测试配置 (低延迟)

```java
KafkaConnectConfig config = new KafkaConnectConfig();
config.setBootstrapServers("localhost:9092");
config.setTopic("your-topic");
config.setGroupId("your-group");

config.setAcks("1");                       // 只等 leader 确认
config.setBatchSize(16384);               // 16KB
config.setLingerMs(0);                    // 立即发送
config.setAutoFlush(true);                // 每条立即 flush
config.setFlushInterval(1);
```

### 4. 多线程 Producer 配置

```java
// 使用 4 个线程，每个线程独立的 EventListenerRegistryManager
ExecutorService executor = Executors.newFixedThreadPool(4);

for (int t = 0; t < 4; t++) {
    final int threadId = t;
    executor.submit(() -> {
        KafkaConnectConfig config = createConfig();
        EventListenerRegistryManager manager = createManager(config);
        manager.start();

        for (int i = 0; i < 10000; i++) {
            EventModel<Event> event = EventModel.build(...);
            manager.publish(EventBusType.KAFKA, event);
        }
    });
}
```

---

## 关键发现

### Async vs Sync 差距巨大
- **ASYNC 模式**: ~50,000 msg/s
- **SYNC 模式**: ~700 msg/s
- **差距**: 71x

**建议**: 生产环境务必使用 ASYNC 模式

### EOS Producer 开销
- Non-EOS: 33,783 msg/s
- EOS: 24,937 msg/s
- **开销约 27%**

**建议**: 对数据一致性要求高的场景启用 EOS

### Consumer 高效
- 直接 KafkaConsumer 可达 70K-100K msg/s
- EventBus Consumer 层开销较小

---

## 测试代码

完整测试代码位于:
- `src/test/java/com/shinyi/eventbus/kafka/KafkaEventBusComprehensiveTest.java`
- `src/test/java/com/shinyi/eventbus/kafka/KafkaEventBusBenchmarkTest.java`

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.3 | 2026-03-26 | 添加 MethodEventListener 参数类型检查警告，优化消费者方法签名建议 |
| 1.1.2 | 2026-03-26 | 修复 KafkaAutoConfiguration 未注入问题，Kafka 配置现在可正确加载 |
| 1.1.1 | 2026-03-21 | 修复 Consumer EOS bug，添加综合测试 |
| 1.1.0 | 2026-03-20 | 初始版本 |
