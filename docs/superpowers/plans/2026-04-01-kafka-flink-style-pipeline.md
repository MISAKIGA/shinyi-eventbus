# Kafka Flink 风格 Pipeline 优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Flink 风格的无锁、无阻塞分区本地处理流水线架构。

**Architecture:**
- 分区本地处理：每个分区独立处理自己的数据，无全局协调
- 无锁设计：不同分区无共享状态竞争
- 流水线并行：poll() 立即返回，不等待分区处理完成
- EOS 分区本地：每个分区独立跟踪 offset，无跨分区同步

**Tech Stack:** Java 8+, Apache Kafka Client, ConcurrentHashMap

---

## 核心架构对比

### 当前设计（有问题）

```
poll() ──→ 分区1 ─┐
                 ├─→ latch.await() ──→ poll()  🔴 全局等待
      分区2 ─────┘
```

### 改进后设计（Flink 风格）

```
poll() ──→ 分区1 ──→ 线程1 (独立处理) ──→ 本地 EOS commit
      │
      └─→ 分区2 ──→ 线程2 (独立处理) ──→ 本地 EOS commit

🔑 关键：无全局等待，poll() 立即返回
```

---

## 文件结构

| 文件 | 变更 |
|------|------|
| `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java` | 重构 ConsumerHandler 和 EosOffsetManager |

---

## Task 1: 重构 EosOffsetManager 为分区本地架构

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

### 当前问题

```java
// 当前设计：所有分区共享同一个 OffsetCommitState
class EosOffsetManager {
    Map<KafkaConsumer, OffsetCommitState> offsetStates;  // 🔴 全局共享
}

// 问题：多分区并发时需要 synchronized(commitLock) 保护
```

### 改进后设计

```java
// 改进后：每个分区独立的 PartitionCommitState
class EosOffsetManager {
    // 🔑 分区级状态，无全局锁竞争
    Map<TopicPartition, PartitionCommitState> states;  // 分区本地状态

    void trackOffset(TopicPartition tp, long offset, int batchSize) {
        PartitionCommitState state = states.computeIfAbsent(tp, k -> new PartitionCommitState());
        state.track(offset);

        // 分区本地 commit，不影响其他分区
        if (state.shouldCommit()) {
            commitPartition(state, tp);
        }
    }
}

class PartitionCommitState {
    long pendingOffset;  // 本分区待提交 offset
    int processedCount;  // 本分区已处理数量
    // 无需 synchronized，本分区状态只在单线程内访问
}
```

### 具体改动

- [ ] **Step 1: 新增 PartitionCommitState 内部类**

在 `EosOffsetManager` 内添加（约第650行）:

```java
/**
 * 分区级 EOS 状态 - Flink 风格分区本地处理
 * 每个分区独立维护自己的 offset 状态，无跨分区协调
 */
private static class PartitionCommitState {
    // 本分区待提交的 offset（下一个待处理位置）
    volatile long pendingOffset = -1;
    // 本分区已处理的消息计数
    AtomicInteger processedCount = new AtomicInteger(0);
    // 本分区是否正在 commit（防止重复 commit）
    AtomicBoolean committing = new AtomicBoolean(false);

    void track(long offset) {
        this.pendingOffset = offset + 1;  // offset + 1 = 下一条待消费位置
        this.processedCount.incrementAndGet();
    }

    boolean shouldCommit(int batchSize) {
        return processedCount.get() >= batchSize && !committing.get();
    }

    boolean tryBeginCommit() {
        return committing.compareAndSet(false, true);
    }

    void resetAfterCommit() {
        processedCount.set(0);
        committing.set(false);
    }
}
```

- [ ] **Step 2: 重构 EosOffsetManager**

删除旧的 `OffsetCommitState` 和 `commitLock`，改为分区级状态:

```java
// 删除旧的:
// private final Map<KafkaConsumer<String, byte[]>, OffsetCommitState> offsetStates = new ConcurrentHashMap<>();
// private final Object commitLock = new Object();

// 替换为:
private final Map<TopicPartition, PartitionCommitState> partitionStates = new ConcurrentHashMap<>();
```

- [ ] **Step 3: 重写 trackOffsetAndCommit 方法**

删除旧的 `trackOffsetAndCommit` 和 `commitPendingOffsets`，替换为分区本地版本:

```java
/**
 * 分区本地 EOS 跟踪 - Flink 风格
 * @param consumer Kafka consumer
 * @param tp TopicPartition
 * @param offset 当前消息的 offset
 * @param batchSize 批量提交大小
 */
void trackOffsetAndCommit(KafkaConsumer<String, byte[]> consumer,
                         TopicPartition tp,
                         long offset,
                         int batchSize) {
    // 获取或创建分区状态
    PartitionCommitState state = partitionStates.computeIfAbsent(tp, k -> new PartitionCommitState());

    // 跟踪 offset
    state.track(offset);

    // 检查是否需要 commit
    if (state.shouldCommit(batchSize)) {
        // 尝试获取 commit 权限
        if (state.tryBeginCommit()) {
            try {
                commitPartition(consumer, tp, state);
            } catch (Exception e) {
                if (!performanceMode) {
                    log.error("EOS: Failed to commit offset for partition {}: {}", tp, e.getMessage());
                }
                // commit 失败，重置状态以便重试
                state.committing.set(false);
            }
        }
        // 如果 tryBeginCommit() 返回 false，说明已经有其他线程在 commit，跳过
    }
}

/**
 * 提交单个分区的 offset
 */
private void commitPartition(KafkaConsumer<String, byte[]> consumer,
                            TopicPartition tp,
                            PartitionCommitState state) {
    try {
        long offsetToCommit = state.pendingOffset;
        if (offsetToCommit >= 0) {
            consumer.commitSync(Collections.singletonMap(tp, new OffsetAndMetadata(offsetToCommit)));
            if (!performanceMode) {
                log.debug("EOS: Committed offset {} for partition {}", offsetToCommit, tp);
            }
        }
    } finally {
        state.resetAfterCommit();
    }
}

/**
 * 提交所有待提交的分区（仅在 shutdown 时调用）
 */
void commitAllPending(KafkaConsumer<String, byte[]> consumer) {
    for (Map.Entry<TopicPartition, PartitionCommitState> entry : partitionStates.entrySet()) {
        TopicPartition tp = entry.getKey();
        PartitionCommitState state = entry.getValue();
        if (state.pendingOffset >= 0) {
            try {
                commitPartition(consumer, tp, state);
            } catch (Exception e) {
                if (!performanceMode) {
                    log.error("EOS: Failed to commit pending offset for partition {}: {}", tp, e.getMessage());
                }
            }
        }
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 编译成功

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java
git commit -m "refactor(kafka): adopt Flink-style partition-local EOS architecture"
```

---

## Task 2: 移除全局 latch，实现流水线处理

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

### 改进后代码结构

```java
while (!Thread.currentThread().isInterrupted()) {
    try {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

        if (records == null || records.isEmpty()) {
            continue;
        }

        // 🔑 关键改进：直接提交任务，不等待
        // 每个分区独立处理，poll() 立即返回
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, byte[]>> partitionRecords = records.records(tp);

            parallelExecutor.submit(() -> {
                for (ConsumerRecord<String, byte[]> record : partitionRecords) {
                    processRecord(record, ...);
                    // 🔑 EOS: 分区本地跟踪
                    if (eosEnabled) {
                        eosManager.trackOffsetAndCommit(
                            consumer, tp, record.offset(), commitBatchSize);
                    }
                }
            });
        }
        // 🔑 立即返回，继续下一轮 poll() - 无阻塞！

    } catch (WakeupException e) {
        break;
    }
}
```

### 具体改动

- [ ] **Step 1: 找到并修改 while 循环代码**

找到 ConsumerHandler 的 while 循环（约第538-593行）。

- [ ] **Step 2: 移除 CountDownLatch 相关代码**

删除:
```java
CountDownLatch latch = new CountDownLatch(recordsByPartition.size());
...
latch.countDown();  // 在 finally 块中
...
latch.await(5, TimeUnit.MINUTES);  // 全局等待
```

- [ ] **Step 3: 修改为分区本地处理**

替换为:
```java
// 直接为每个分区提交任务，不等待
for (TopicPartition tp : records.partitions()) {
    List<ConsumerRecord<String, byte[]>> partitionRecords = records.records(tp);

    parallelExecutor.submit(() -> {
        for (ConsumerRecord<String, byte[]> record : partitionRecords) {
            processRecord(record, finalListener, eosEnabled, eosManager, consumer, commitBatchSize, deserializeFn);
            // 🔑 EOS: 分区本地跟踪
            if (eosEnabled) {
                eosManager.trackOffsetAndCommit(consumer, tp, record.offset(), commitBatchSize);
            }
        }
    });
}
// 🔑 立即返回，poll() 无阻塞
```

- [ ] **Step 4: 删除不再需要的 processRecord 参数**

原来的 `processRecord` 调用可能需要调整，因为现在需要传递 `TopicPartition tp`。

修改 `processRecord` 方法签名:
```java
private void processRecord(ConsumerRecord<String, byte[]> record,
                          TopicPartition tp,  // 新增参数
                          com.shinyi.eventbus.EventListener<T> listener,
                          boolean eosEnabled,
                          EosOffsetManager eosManager,
                          KafkaConsumer<String, byte[]> consumer,
                          int commitBatchSize,
                          DeserializeFunction<T> deserializeFn) {
    // ... 原有逻辑 ...
}
```

- [ ] **Step 5: 修改 processRecord 调用处**

更新所有调用处，传入 `tp` 参数。

- [ ] **Step 6: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 编译成功

- [ ] **Step 7: 提交**

```bash
git commit -m "feat(kafka): remove global latch for true pipeline processing"
```

---

## Task 3: 更新 shutdown 逻辑

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

### 改进后 shutdown

```java
void shutdown(EosOffsetManager eosManager, boolean performanceMode) {
    // 1. 停止接受新任务
    for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
        consumer.wakeup();  // 唤醒 poll()，使其抛出 WakeupException
    }

    // 2. 等待线程池任务完成
    for (ExecutorService executor : executorSet) {
        executor.shutdownNow();
    }

    // 3. EOS: 提交所有待提交的分区 offset
    for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
        if (eosManager != null) {
            eosManager.commitAllPending(consumer);  // 🔑 使用新的方法
        }
        consumer.close();
    }
}
```

- [ ] **Step 1: 修改 shutdown 方法**

更新 `shutdown` 方法调用 `commitAllPending` 而非原来的 `commitPendingOffsets`。

- [ ] **Step 2: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 3: 提交**

```bash
git commit -m "refactor(kafka): update shutdown to use commitAllPending"
```

---

## Task 4: 添加单元测试

**Files:**
- Modify: `src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java`

- [ ] **Step 1: 添加分区本地状态测试**

```java
@Test
public void testPartitionCommitState() throws Exception {
    // 验证 PartitionCommitState 的行为
    // 使用反射测试私有内部类

    // 测试 track 和 shouldCommit
    // ...

    // 测试 tryBeginCommit 防止重复 commit
    // ...
}

@Test
public void testEosOffsetManagerNoGlobalLock() {
    // 验证：不同分区的 commit 不会相互阻塞
    // 这是架构验证，主要确保无全局状态
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -DskipTests=false -Dtest=KafkaParallelConsumeTest -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 3: 提交**

```bash
git commit -m "test(kafka): add Flink-style pipeline tests"
```

---

## Task 5: 端到端验证

- [ ] **Step 1: 运行所有测试**

```bash
mvn test -DskipTests=false -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml 2>&1 | grep -E "(Tests run|BUILD|FAILURE)"
```

- [ ] **Step 2: 编译打包**

```bash
mvn clean package -DskipTests -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml && echo "BUILD SUCCESS"
```

- [ ] **Step 3: 检查 git log**

```bash
git log --oneline -10
```

---

## 架构对比总结

### 改进前 vs 改进后

| 特性 | 改进前 | 改进后 (Flink 风格) |
|------|--------|---------------------|
| **poll() 等待** | `latch.await()` 全局阻塞 | 无等待，立即返回 |
| **EOS 处理** | 全局 `synchronized(commitLock)` | 分区本地，无锁竞争 |
| **故障影响** | 所有分区等待最慢分区 | 只影响单分区 |
| **吞吐量** | 受限于最慢分区 | 分区独立，互不影响 |
| **复杂度** | 需理解全局状态 | 分区隔离，简单 |
| **可维护性** | 全局协调复杂 | 分区本地，简单 |

### 关键改进

1. **移除全局 latch** - poll() 无阻塞
2. **分区本地 EOS** - 无跨分区锁竞争
3. **线程安全** - 使用 `AtomicInteger` 和 `AtomicBoolean` 保证分区本地安全
4. **无死锁风险** - 移除 `synchronized(commitLock)`

### 性能提升预期

```
场景: 10 分区，1 分区处理慢

改进前:
  分区1: 1000 msg/s
  分区2: 1000 msg/s
  分区3: 10 msg/s  ← 慢分区
  整体吞吐量: ~30 msg/s (被慢分区拖累)

改进后:
  分区1: 1000 msg/s
  分区2: 1000 msg/s
  分区3: 10 msg/s (独立处理，不影响其他)
  整体吞吐量: ~2000+ msg/s
```

---

## 风险评估

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| EOS 语义被破坏 | 低 | 分区本地 commit 逻辑简单，可验证 |
| 内存压力 | 低 | 每个分区独立状态，无全局累积 |
| 测试覆盖 | 中 | 添加单元测试验证分区隔离 |
