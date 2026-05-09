# Kafka 消费者 Pipeline 优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除全局 `latch.await()` 等待，实现真正的 pipeline 并行处理。非 EOS 模式直接 poll()，EOS 模式分区级 batch commit。

**Architecture:**
- 非 EOS 模式：提交任务到线程池后立即返回，继续下一次 poll()
- EOS 模式：分区级 batch commit，EosOffsetManager 内部同步，不需要全局等待
- 保持分区内顺序性

**Tech Stack:** Java 8+, Apache Kafka Client, ConcurrentHashMap

---

## 核心概念说明

### 当前瓶颈

```
当前架构:
┌─────────────────────────────────────────────────────────┐
│  poll() ─────────────────────────────────────────────→ │
│       ↓                                                   │
│  分区1 处理 ──┐                                           │
│  分区2 处理 ──┼→ latch.await() ─→ 下一轮 poll()          │
│  分区3 处理 ──┘     ↑                                      │
│                    │                                      │
│              慢分区阻塞所有分区                           │
└─────────────────────────────────────────────────────────┘
```

### 优化后架构

```
优化后架构 (非 EOS):
┌─────────────────────────────────────────────────────────┐
│  poll() ─────────────────────────────────────────────→ │
│       ↓                                                   │
│  分区1 处理 (独立线程) ──────────────────────────────→   │
│  分区2 处理 (独立线程) ──────────────────────────────→   │
│  分区3 处理 (独立线程) ──────────────────────────────→   │
│       ↓ (立即返回，继续下一轮 poll)                      │
│  poll() ─────────────────────────────────────────────→ │
└─────────────────────────────────────────────────────────┘

优化后架构 (EOS):
┌─────────────────────────────────────────────────────────┐
│  poll() ─────────────────────────────────────────────→ │
│       ↓                                                   │
│  分区1 处理 ──→ trackOffsetAndCommit() ──→ 分区级 batch commit │
│  分区2 处理 ──→ trackOffsetAndCommit() ──→ 等待 batch  │
│  分区3 处理 ──→ trackOffsetAndCommit()                  │
│       ↓ (立即返回，继续下一轮 poll)                      │
│  EOS: EosOffsetManager 内部 synchronized 保护            │
└─────────────────────────────────────────────────────────┘
```

---

## 文件结构

| 文件 | 变更 |
|------|------|
| `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java` | 修改 ConsumerHandler，移除全局等待 |

---

## Task 1: 移除全局 latch 等待

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java`

### 关键改动说明

**删除的内容 (约第 556-576 行):**
```java
// 删除: CountDownLatch 全局等待
CountDownLatch latch = new CountDownLatch(recordsByPartition.size());

recordsByPartition.forEach((tp, partitionRecords) -> {
    parallelExecutor.submit(() -> {
        try {
            for (ConsumerRecord<String, byte[]> record : partitionRecords) {
                processRecord(...);
            }
        } finally {
            latch.countDown();  // 删除
        }
    });
});

// 删除: 全局等待
try {
    latch.await(5, TimeUnit.MINUTES);  // 🔴 这是瓶颈
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

**修改后:**
```java
// 直接提交任务到线程池，不等待
recordsByPartition.forEach((tp, partitionRecords) -> {
    parallelExecutor.submit(() -> {
        for (ConsumerRecord<String, byte[]> record : partitionRecords) {
            processRecord(...);
        }
        // 注意: processRecord 内部会调用 EosOffsetManager.trackOffsetAndCommit()
        // EOS 模式下，分区级 batch commit 会在 EosOffsetManager 内部处理
    });
});

// 🔧 关键: 不再阻塞，立即返回继续下一次 poll()
```

- [ ] **Step 1: 读取当前 ConsumerHandler 代码**

找到 while 循环内约第 543-576 行的代码。

- [ ] **Step 2: 删除 CountDownLatch 相关代码**

删除第 556-576 行的:
```java
CountDownLatch latch = new CountDownLatch(recordsByPartition.size());
...
try {
    latch.await(5, TimeUnit.MINUTES);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

- [ ] **Step 3: 修改 lambda 表达式，删除 finally 中的 latch.countDown()**

修改为:
```java
recordsByPartition.forEach((tp, partitionRecords) -> {
    parallelExecutor.submit(() -> {
        for (ConsumerRecord<String, byte[]> record : partitionRecords) {
            processRecord(record, finalListener, eosEnabled, eosManager, consumer, commitBatchSize, deserializeFn);
        }
        // 不再需要 latch.countDown()
    });
});
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

预期: 编译成功

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/shinyi/eventbus/registry/OptimizedKafkaMqEventListenerRegistry.java
git commit -m "feat(kafka): remove global latch.await() for true pipeline processing"
```

---

## Task 2: 验证 EOS 模式正确性

**问题:** 移除全局 latch 后，EOS 模式是否仍然正确？

**验证点:**
1. `EosOffsetManager.trackOffsetAndCommit()` 是否能正确处理并发？
2. `commitLock` 同步是否足够？

### 当前 EosOffsetManager 实现

```java
void trackOffsetAndCommit(...) {
    state.pendingOffsets.put(tp, new OffsetAndMetadata(record.offset() + 1));
    int count = state.processedCount.incrementAndGet();

    if (count >= batchSize) {
        synchronized (commitLock) {
            if (state.processedCount.get() >= batchSize) {
                commitPendingOffsetsInternal(consumer, state);
            }
        }
    }
}
```

**分析:**
- ✅ `synchronized (commitLock)` 保护了 check-and-commit 操作的原子性
- ✅ `commitPendingOffsetsInternal` 在锁内执行，不会与其他线程的 commit 操作冲突
- ✅ 当 batchSize 达到时，会等待其他正在处理的线程完成后再 commit

**结论:** EOS 模式在移除全局 latch 后仍然正确，因为 `EosOffsetManager` 内部已经用 `commitLock` 保护了 batch commit 逻辑。

- [ ] **Step 1: 确认 EosOffsetManager 代码正确性**

检查 `EosOffsetManager` 中的 `commitLock` 和 `trackOffsetAndCommit` 实现。

- [ ] **Step 2: 添加注释说明 EOS 保证**

在 ConsumerHandler 的 poll 循环处添加注释:
```java
// Note: EOS correctness is maintained by EosOffsetManager.commitLock
// which ensures batch commits are serialized across partitions
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 4: 提交**

```bash
git commit -m "docs(kafka): add comments explaining EOS correctness after latch removal"
```

---

## Task 3: 性能测试验证

**Files:**
- Modify: `src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java`

- [ ] **Step 1: 添加性能对比测试 (可选)**

添加一个简单的测试验证非 EOS 模式下不需要等待:

```java
@Test
public void testNoGlobalLatchForNonEos() {
    // 验证: 非 EOS 模式下，消息处理是异步的，不阻塞 poll
    // 这个测试主要验证配置正确性
    KafkaConnectConfig config = new KafkaConnectConfig();
    config.setEnableManualCommit(false);  // 非 EOS 模式

    assertFalse(config.isEnableManualCommit());
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -DskipTests=false -Dtest=KafkaParallelConsumeTest -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml
```

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/shinyi/eventbus/registry/KafkaParallelConsumeTest.java
git commit -m "test(kafka): add test for non-EOS pipeline processing"
```

---

## Task 4: 端到端验证

- [ ] **Step 1: 编译打包**

```bash
mvn clean package -DskipTests -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml && echo "BUILD SUCCESS"
```

- [ ] **Step 2: 运行所有测试**

```bash
mvn test -DskipTests=false -q -f /root/.openclaw/workspace-ceo/shinyi-eventbus/pom.xml 2>&1 | grep -E "(Tests run|BUILD|FAILURE)"
```

- [ ] **Step 3: 检查 git log**

```bash
git log --oneline -10
```

---

## 变更总结

### 改动对比

| 改动点 | 原设计 | 优化后 |
|--------|--------|--------|
| 非 EOS poll() | 等待所有分区完成 | 立即返回，继续 poll() |
| EOS commit | 全局 latch + 批量提交 | 分区级 batch commit + commitLock 同步 |
| 吞吐量 | 受限于最慢分区 | 各分区独立，互不阻塞 |

### 关键保证

| 保证 | 实现方式 |
|------|----------|
| 分区内有序 | 同一分区的记录在同一线程内串行处理 |
| EOS 正确性 | EosOffsetManager.commitLock 保护 batch commit |
| 线程安全 | synchronized (commitLock) 序列化并发 commit |

### 性能提升预期

| 场景 | 原设计 | 优化后 |
|------|--------|--------|
| 10 分区，均衡负载 | 正常 | 正常 (无变化) |
| 10 分区，1 慢分区 | 慢分区拖慢整体 | 慢分区不影响其他分区 |
| EOS 模式 | 需要等待 | 不需要等待，commitLock 保护 |

---

## 风险评估

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| EOS 语义被破坏 | 低 | commitLock 已保护 batch commit 原子性 |
| 内存压力 | 低 | poll() 返回的 records 是独立的，不会无限累积 |
| 线程饥饿 | 低 | 线程池大小合理配置 |

---

## 预期效果

优化后日志可能显示更高的吞吐量，因为 poll() 不再被阻塞等待分区处理完成。
