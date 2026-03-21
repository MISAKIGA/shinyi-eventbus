# PRD: Kafka EOS 修复与性能优化

**版本**: 1.1.0
**日期**: 2026-03-21
**状态**: 待实施

---

## 1. 问题陈述

### 1.1 Consumer EOS 功能失效 (CRITICAL BUG)

**问题**: `@EventBusListener(exactlyOnce=true)` 注解设置**未被传递**到 `MethodEventListener`。

**根本原因**: `EventListenerRegistryManager.eventListenerRegister()` 创建 `MethodEventListener` 时未传递 `exactlyOnce` 和 `commitBatchSize` 参数。

**影响**: 即使用户设置 `exactlyOnce=true`，Consumer EOS 也不会生效。

```java
// EventListenerRegistryManager.java (问题代码)
EventListener<EventModel<Object>> listener = new MethodEventListener(
    bean, executeMethod,
    topic, entityType, group,
    // ... 缺少 exactlyOnce 和 commitBatchSize
);
```

### 1.2 性能差距

| 实现 | 吞吐量 | 差距 |
|------|--------|------|
| kafka-demo (Pure Kafka) | ~50,000 msg/s | - |
| EventBus RAW Producer | ~4,300 msg/s | **11.5x 慢** |

**根本原因**: EventBus 抽象层开销 (MDC、Map查找、对象创建、监控)

### 1.3 集成测试缺失

- `@EventBusListener` 消费路径**未测试** (使用直接 KafkaConsumer)
- EOS 端到端验证**缺失**

---

## 2. 解决方案

### 2.1 修复 Consumer EOS Bug

#### Phase 1: 传递 EOS 配置到 MethodEventListener

**Step 1.1**: 修改 `MethodEventListener` 构造函数添加 EOS 参数

```java
// MethodEventListener.java
private final boolean exactlyOnce;
private final int commitBatchSize;

public MethodEventListener(..., boolean exactlyOnce, int commitBatchSize) {
    this.exactlyOnce = exactlyOnce;
    this.commitBatchSize = commitBatchSize;
}

@Override
public boolean exactlyOnce() { return this.exactlyOnce; }

@Override
public int commitBatchSize() { return this.commitBatchSize; }
```

**Step 1.2**: 修改 `EventListenerRegistryManager.eventListenerRegister()` 传递 EOS 参数

```java
// EventListenerRegistryManager.java
EventListener<EventModel<Object>> listener = new MethodEventListener(
    bean, executeMethod,
    env.resolveRequiredPlaceholders(eventListener.topic()),
    eventListener.entityType(),
    env.resolveRequiredPlaceholders(eventListener.group()),
    // ... 其他参数
    eventListener.exactlyOnce(),    // 新增
    eventListener.commitBatchSize() // 新增
);
```

**Step 1.3**: 添加全局配置 Fallback

```java
// KafkaMqEventListenerRegistry.initConsumer()
final boolean eosEnabled = listener.exactlyOnce()
    || kafkaConnectConfig.isEnableManualCommit();
final int commitBatchSize = listener.commitBatchSize() > 0
    ? listener.commitBatchSize()
    : kafkaConnectConfig.getCommitBatchSize();
```

### 2.2 性能优化

#### Phase 2: 性能基准对齐

**Step 2.1**: 修复 `createBaselineConfig()` 使用 `acks="all"`

```java
// KafkaEventBusBenchmarkTest.java
config.setAcks("all");  // 对齐 kafka-demo
```

**Step 2.2**: 添加性能对比测试

| Test | 配置 | 预期 |
|------|------|------|
| Baseline (EventBus) | EventBus API, sync | ~4K msg/s |
| RAW (EventBus aligned) | Direct Kafka, kafka-demo config | ~14K msg/s |
| Pure Kafka | Direct Kafka Client | ~50K msg/s |

### 2.3 EOS 集成测试

#### Phase 3: 端到端 EOS 测试

**Test 1: @EventBusListener exactlyOnce 无重复**

```java
@Test
void testEventBusListenerExactlyOnceNoDuplicates() {
    // 1. 创建 @EventBusListener(exactlyOnce=true)
    // 2. 发布 10000 条唯一消息
    // 3. 模拟 crash (消费到一半停止)
    // 4. 重启 consumer
    // 5. 验证: 无重复, 无丢失
}
```

**Test 2: 混合 EOS 设置隔离**

```java
@Test
void testEventBusListenerMixedEosSettings() {
    // 1. 两个 listener 同一 topic
    // 2. Listener A: exactlyOnce=true
    // 3. Listener B: exactlyOnce=false
    // 4. 验证 A 无重复, B 可能有重复
}
```

---

## 3. 验收标准

### 3.1 Consumer EOS Bug 修复

| ID | 验收标准 | 测试方法 |
|----|----------|----------|
| AC-1 | `@EventBusListener(exactlyOnce=true)` 能正确启用 EOS | 单元测试 |
| AC-2 | `commitBatchSize=50` 能正确提交 offset | 单元测试 |
| AC-3 | 全局 `enableManualCommit=true` 能启用 EOS | 集成测试 |
| AC-4 | 非 EOS listener 不受影响 | 回归测试 |

### 3.2 性能

| ID | 验收标准 | 目标 |
|----|----------|------|
| AC-5 | Baseline producer 吞吐量 | >4,000 msg/s |
| AC-6 | EOS producer 无显著性能下降 | <5% overhead |
| AC-7 | Multi-threaded producer 吞吐量 | >10,000 msg/s |

### 3.3 EOS 语义保证

| ID | 验收标准 | 测试方法 |
|----|----------|----------|
| AC-8 | exactlyOnce=true 时无消息重复 | EOS 集成测试 |
| AC-9 | exactlyOnce=true 时无消息丢失 | EOS 集成测试 |
| AC-10 | Crash 恢复后正确继续 | Crash 恢复测试 |
| AC-11 | Rebalance 不丢失消息 | Rebalance 测试 |

### 3.4 向后兼容

| ID | 验收标准 | 测试方法 |
|----|----------|----------|
| AC-12 | 现有非 EOS listener 继续正常工作 | 回归测试 |
| AC-13 | 现有配置继续有效 | 配置兼容性测试 |

---

## 4. 测试矩阵

| Test | AC-1 | AC-2 | AC-3 | AC-4 | AC-8 | AC-9 | AC-10 | AC-11 |
|------|------|------|------|------|------|------|-------|-------|
| `KafkaMqEventListenerRegistryTest` | ✓ | ✓ | ✓ | ✓ | | | | |
| `KafkaEventBusIntegrationTest` | | | | | ✓ | ✓ | | |
| `KafkaEosCrashRecoveryTest` | | | | | | | ✓ | |
| `KafkaEosRebalanceTest` | | | | | | | | ✓ |

---

## 5. 实施计划

### Sprint 1: Bug 修复
- [ ] 修改 MethodEventListener 添加 EOS 参数
- [ ] 修改 EventListenerRegistryManager 传递 EOS 参数
- [ ] 添加单元测试验证
- [ ] 验证所有现有测试通过

### Sprint 2: 性能基准
- [ ] 修复 baseline config acks=all
- [ ] 添加性能对比测试
- [ ] 文档化性能特征

### Sprint 3: EOS 集成测试
- [ ] 创建 KafkaEosIntegrationTest
- [ ] 实现无重复测试
- [ ] 实现 Crash 恢复测试
- [ ] 实现 Rebalance 测试

---

## 6. 风险

| Risk | Impact | Mitigation |
|------|--------|------------|
| 修改 MethodEventListener 可能破坏现有功能 | High | 全面回归测试 |
| 性能测试在 CI 环境不稳定 | Medium | 使用 Testcontainers |
| EOS 测试 timing 相关 flaky | Medium | generous timeout |

---

## 7. 非目标 (Out of Scope)

- 不优化 EventBus 抽象层性能 (需要单独的项目)
- 不改变 KafkaConnectConfig 的现有配置结构
- 不实现分布式事务 (2PC)

---

## 8. 附录

### A. 相关文件

| File | Change |
|------|--------|
| `MethodEventListener.java` | 添加 exactlyOnce, commitBatchSize 参数 |
| `EventListenerRegistryManager.java` | 传递 EOS 参数 |
| `KafkaMqEventListenerRegistry.java` | 添加全局 fallback |
| `KafkaEosIntegrationTest.java` | **新建** |

### B. 参考文档

- `doc/phase2-eos-annotation-design.md`
- `doc/kafka-optimization-architecture.md`
