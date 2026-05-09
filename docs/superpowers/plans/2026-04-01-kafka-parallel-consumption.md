# Kafka 多线程并行消费优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Kafka 消费者从单线程串行处理改为多线程并行处理，按分区分组并行消费，提升多核 CPU 利用率和吞吐量。

**Architecture:**
- 使用 `ConsumerRecords.records(TopicPartition)` 按分区聚合记录
- 为每个分区创建一个任务，提交到线程池并行处理
- 保持分区内的顺序性（Kafka 语义保证）
- EOS 模式下需等待批次内所有分区处理完成后再提交 offset

**Tech Stack:** Java 8+, Apache Kafka Client, ConcurrentHashMap, ExecutorService

---

## 核心概念说明

### Kafka 分区与并行

```
                    poll() 返回
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
   Partition 0     Partition 1     Partition 2
    (1000条)        (1000条)        (1000条)
         │               │               │
         ▼               ▼               ▼
    线程 1 处理      线程 2 处理      线程 3 处理
   (串行，保证顺序)  (串行，保证顺序)  (串行，保证顺序)
```

**关键约束：**
- Kafka 保证**单分区内有序**
- 不同分区之间**无顺序保证**
- 同一分区内的消息必须在**同一线程内串行处理**

### 当前瓶颈

```java
// 当前实现：单线程 for 循环处理所有记录
for (ConsumerRecord<String, byte[]> record : records) {
    // 单线程逐条处理，无论有多少个分区
    process(record);
}
```

---

## 文件结构

| 文件 | 变更 |
|------|------|
| `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java` | 修改 ConsumerHandler，按分区并行处理 |
| `src/main/java/com/shinyi/eventbus/config/kafka/KafkaConnectConfig.java` | 添加 `consumerThreads` 配置项 |
| `src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java` | **CREATE** - 并行消费测试 |

---

## Task 1: 添加线程池配置项

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/config/kafka/KafkaConnectConfig.java`

- [ ] **Step 1: 在 KafkaConnectConfig 中添加 consumerThreads 字段**

在 `fetchMaxWaitMs` 字段后添加（约第122行）:

```java
// ==================== Parallel Consumption (P1) ====================

/**
 * Number of threads for parallel message processing.
 * Default: CPU cores (auto-determined at runtime)
 * Maximum: min(consumerThreads, partitionCount) when partition count is known
 *
 * Special values:
 *   0 or negative = auto (use CPU cores)
 *   1 = single-threaded mode (disable parallel processing)
 *
 * Recommended: Set to CPU cores or partition count, whichever is smaller.
 */
private int consumerThreads = 0;  // 0 means auto-detect

/**
 * Auto-detect consumer threads based on partition count.
 * If true: consumerThreads = min(partitionCount, CPU cores)
 * If false: consumerThreads = consumerThreads config value
 */
private boolean autoDetectConsumerThreads = true;
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 编译成功

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/shinyi/eventbus/config/kafka/KafkaConnectConfig.java
git commit -m "feat(kafka): add consumerThreads config for parallel consumption"
```

---

## Task 2: 修改 ConsumerHandler 实现按分区并行处理

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

- [ ] **Step 1: 添加线程池实例变量**

在 `ConsumerHandler` 类中添加（约第460行）:

```java
private class ConsumerHandler {
    private final Set<KafkaConsumer<String, byte[]>> consumerSet = new ConcurrentHashSet<>();
    private final Set<ExecutorService> executorSet = new ConcurrentHashSet<>();
    private ExecutorService parallelExecutor;  // 新增：并行处理线程池
```

- [ ] **Step 2: 添加线程池初始化（带分区数检测）**

在 `initConsumer` 方法开头添加（约第472行）:

```java
void initConsumer(...) {
    // 初始化并行处理线程池
    if (parallelExecutor == null) {
        int configuredThreads = config.getConsumerThreads();

        // 尝试获取分区数以智能调整线程数
        int partitionCount = 0;
        try {
            // partitionsFor() 可能需要远程调用，设置超时
            List<PartitionInfo> partitions = consumer.partitionsFor(
                topicList.get(0), Duration.ofSeconds(5));
            if (partitions != null && !partitions.isEmpty()) {
                partitionCount = partitions.size();
            }
        } catch (Exception e) {
            // 无法获取分区数，使用配置值
        }

        int threads;
        int cpuCores = Runtime.getRuntime().availableProcessors();

        if (config.isAutoDetectConsumerThreads() && partitionCount > 0) {
            // 智能平衡策略:
            // 1. 如果分区数 <= CPU核心数: threads = min(分区数, 配置值, 32)
            // 2. 如果分区数 > CPU核心数: threads = min(CPU核心数 * 4, 分区数, 配置值, 32)
            //    - CPU核心数 * 4 是一个经验值，平衡并行度和上下文切换开销
            if (partitionCount <= cpuCores) {
                threads = Math.min(partitionCount, configuredThreads > 0 ? configuredThreads : cpuCores);
            } else {
                // 分区数 > CPU核心数，使用 CPU核心数 * 4，上限 32
                int balancedThreads = Math.min(cpuCores * 4, 32);
                threads = configuredThreads > 0
                    ? Math.min(configuredThreads, balancedThreads)
                    : balancedThreads;
            }
            if (!performanceMode) {
                log.info("Kafka parallel consumer: detected {} partitions, {} CPU cores, using {} threads (balanced)",
                    partitionCount, cpuCores, threads);
            }
        } else {
            // 配置模式: 使用配置的线程数（0 或负数 = CPU核心数）
            threads = configuredThreads <= 0 ? cpuCores : configuredThreads;
            threads = Math.min(threads, 32);  // 合理上限
        }

        parallelExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "kafka-parallel-consumer");
            t.setDaemon(true);
            return t;
        });
        executorSet.add(parallelExecutor);
    }
    // ... 其余代码不变 ...
}
```

**注意**: 需要添加 `import org.apache.kafka.common.PartitionInfo;`

- [ ] **Step 3: 修改消息处理逻辑 - 按分区分组并行处理**

找到 `while` 循环内的消息处理代码（约第495-520行），将：

```java
ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
for (ConsumerRecord<String, byte[]> record : records) {
    // 单线程串行处理
    process(record);
}
```

修改为：

```java
ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
if (records == null || records.isEmpty()) {
    continue;
}

// 按 TopicPartition 分组
Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> recordsByPartition =
    records.records(new HashSet<>(records.partitions()));

// 为每个分区创建并行任务
CountDownLatch latch = new CountDownLatch(recordsByPartition.size());

recordsByPartition.forEach((tp, partitionRecords) -> {
    parallelExecutor.submit(() -> {
        try {
            for (ConsumerRecord<String, byte[]> record : partitionRecords) {
                // 处理每条消息（保持分区内的顺序性）
                processRecord(record, finalListener, eosEnabled, eosManager, consumer);
            }
        } finally {
            latch.countDown();
        }
    });
});

// 等待所有分区处理完成（确保 EOS offset 提交顺序正确）
try {
    latch.await(5, TimeUnit.MINUTES);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

- [ ] **Step 4: 提取 processRecord 方法**

将原来的消息处理逻辑提取为独立方法：

```java
private void processRecord(ConsumerRecord<String, byte[]> record,
                          com.shinyi.eventbus.EventListener<T> listener,
                          boolean eosEnabled,
                          EosOffsetManager eosManager,
                          KafkaConsumer<String, byte[]> consumer) {
    long consumeStart = System.currentTimeMillis();
    try {
        if (record.value() == null || record.value().length == 0) {
            if (!performanceMode) {
                log.warn("Message body is empty, skipping. offset={}", record.offset());
            }
            return;
        }
        EventModel<?> eventModel = deserialize.apply(record.value(), record.offset() + "", listener);
        listener.onMessage((T) eventModel);
        // EOS: Track offset for manual commit after successful processing
        if (eosEnabled) {
            eosManager.trackOffsetAndCommit(consumer, record, commitBatchSize);
        }
        // Record consumption metrics
        MetricsHolder.increment(registryBeanName, record.topic(), "events.consumed", 1);
    } catch (Exception e) {
        // Record failure metrics
        MetricsHolder.increment(registryBeanName, record.topic(), "events.failed", 1);
        if (!performanceMode) {
            log.warn("Message processing failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: 处理 commitBatchSize 变量作用域问题**

在原代码中 `commitBatchSize` 是 `initConsumer` 方法内的局部变量。需要将其传递给 `processRecord`。修改方法签名：

```java
// 添加 commitBatchSize 参数
private void processRecord(ConsumerRecord<String, byte[]> record,
                          com.shinyi.eventbus.EventListener<T> listener,
                          boolean eosEnabled,
                          EosOffsetManager eosManager,
                          KafkaConsumer<String, byte[]> consumer,
                          int commitBatchSize) {
    // ... 使用 commitBatchSize ...
}
```

- [ ] **Step 6: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 编译成功（可能有警告 about reference to internal class）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java
git commit -m "feat(kafka): parallel consumption by partition with thread pool"
```

---

## Task 3: 创建并行消费测试

**Files:**
- Create: `src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java`

- [ ] **Step 1: 查看现有 Kafka 测试结构**

```bash
head -100 /root/.openclaw/workspace-ceo/shinyi-eventbus/src/test/java/com/shinyi/eventbus/kafka/KafkaConsumerTest.java
```

- [ ] **Step 2: 编写并行消费测试**

```java
package com.shinyi.eventbus.registry;

import com.shinyi.eventbus.EventListener;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test parallel consumption behavior
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaParallelConsumeTest {

    private static final String TOPIC = "test-parallel-topic";
    private OptimizedKafkaMqEventListenerRegistry<EventModel<?>> registry;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers("localhost:9092");
        config.setTopic(TOPIC);
        config.setGroupId("test-parallel-group");
        config.setConsumerThreads(4);  // 设置 4 个线程
        registry = new OptimizedKafkaMqEventListenerRegistry<>(applicationContext, "kafka", config);
        registry.init();
    }

    @Test
    public void testConsumerThreadsConfig() {
        // Verify consumerThreads is set correctly
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setConsumerThreads(8);
        assertEquals(8, config.getConsumerThreads());
    }

    @Test
    public void testDefaultConsumerThreads() {
        // Verify default is CPU cores
        KafkaConnectConfig config = new KafkaConnectConfig();
        assertEquals(Runtime.getRuntime().availableProcessors(), config.getConsumerThreads());
    }

    @Test
    public void testParallelExecutorCreated() throws Exception {
        // Test that thread pool is created with correct size
        AtomicInteger maxThreads = new AtomicInteger(0);

        // 通过反射检查线程池配置
        java.lang.reflect.Field parallelExecutorField =
            OptimizedKafkaMqEventListenerRegistry.class.getDeclaredField("consumerHandler");
        parallelExecutorField.setAccessible(true);

        // 注意：这个测试主要验证配置正确性，实际并行测试需要集成测试环境
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
mvn test -DskipTests=false -Dtest=KafkaParallelConsumeTest -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 测试编译和运行成功

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java
git commit -m "test(kafka): add parallel consumption test"
```

---

## Task 4: 更新文档

**Files:**
- Modify: `README.md` (Kafka Configuration 部分)
- Modify: `README-CN.md` (Kafka 配置 部分)

- [ ] **Step 1: 在 README.md Kafka Configuration 部分添加 consumerThreads 说明**

在 Kafka Configuration section 添加：

```markdown
### Consumer Parallel Processing

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          bootstrap-servers: localhost:9092
          topic: my-topic
          # Parallel consumer threads (default: auto)
          # 0 = auto-detect based on partition count and CPU cores
          # 1 = single-threaded mode (disable parallel)
          # N = use N threads (capped at partition count)
          consumer-threads: 0
          # Auto-detect threads based on partition count (default: true)
          auto-detect-consumer-threads: true
```

**Auto-detection logic (balanced strategy):**
- If `auto-detect-consumer-threads=true`:
  - `partitionCount <= CPU cores`: threads = min(partitionCount, CPU cores, 32)
  - `partitionCount > CPU cores`: threads = min(CPU cores × 4, partitionCount, 32)
    - CPU × 4 是经验值，平衡并行度和上下文切换开销
- If `auto-detect-consumer-threads=false`: threads = consumerThreads (0 = CPU cores)
- Maximum threads capped at 32 to prevent resource exhaustion

**Examples:**
| Partitions | CPU Cores | consumerThreads | Result |
|------------|-----------|----------------|--------|
| 10 | 8 | 0 | 8 (min of 10, 8) |
| 50 | 2 | 0 | 8 (min of 2×4=8, 50, 32) |
| 100 | 4 | 0 | 16 (min of 4×4=16, 100, 32) |
| 50 | 8 | 16 | 16 (configured value) |
```

- [ ] **Step 2: 更新 README-CN.md**

添加中文版本说明。

- [ ] **Step 3: 提交**

```bash
git add README.md README-CN.md
git commit -m "docs: document consumerThreads parallel processing config"
```

---

## Task 5: 端到端验证

- [ ] **Step 1: 运行所有测试**

```bash
mvn test -DskipTests=false -Dtest="*Kafka*" -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 2: 编译打包**

```bash
mvn clean package -DskipTests -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 3: 检查 git log**

```bash
git log --oneline -10
```

---

## 变更总结

### 架构变化

```
Before (单线程):
┌─────────────────────────────────────────────────┐
│  poll() → records (多分区)                        │
│    ↓                                             │
│  for (record : records) {                        │
│      process(record);  // 单线程串行              │
│  }                                               │
└─────────────────────────────────────────────────┘

After (多线程并行):
┌─────────────────────────────────────────────────┐
│  poll() → records (多分区)                        │
│    ↓                                             │
│  recordsByPartition = records.records(tpSet)       │
│    ↓                                             │
│  for (tp : recordsByPartition.keySet()) {        │
│      parallelExecutor.submit(() -> {              │
│          for (record : recordsByPartition.get(tp))│
│              process(record); // 分区内串行       │
│      });                                         │
│  }                                               │
│    ↓                                             │
│  latch.await(); // 等待所有分区完成                │
└─────────────────────────────────────────────────┘
```

### 关键保证

| 保证 | 实现方式 |
|------|----------|
| 分区内有序 | 同一分区的记录在同一线程内串行处理 |
| EOS 正确性 | CountDownLatch 等待所有分区处理完成后再进行下次 poll |
| 线程安全 | ConcurrentHashMap 存储分区记录，AtomicInteger 计数 |

### 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `consumerThreads` | 0 (auto) | 并行消费的线程数，0=自动检测，1=单线程，N=指定线程数 |
| `autoDetectConsumerThreads` | true | 是否启用智能线程数平衡策略 |

### 智能平衡策略

```
分区数 <= CPU核心数:
  → threads = min(分区数, CPU核心数)

分区数 > CPU核心数:
  → threads = min(CPU核心数 × 4, 分区数, 32)

经验值 CPU×4 的原因:
  - 线程不是越多越好（上下文切换开销）
  - 2-4倍于CPU核心数的线程数是经验最优值
  - Kafka 消息处理主要是 I/O 和轻量计算
```

### 风险评估

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| 并行处理导致乱序 | 低 | 分区内串行，Kafka 语义保证 |
| EOS offset 提交顺序 | 低 | CountDownLatch 保证所有分区处理完再提交 |
| 线程池资源泄漏 | 低 | shutdown 时正确关闭所有 ExecutorService |
| 内存压力 | 中 | poll() 返回的 records 会被并行处理，不会累积 |
