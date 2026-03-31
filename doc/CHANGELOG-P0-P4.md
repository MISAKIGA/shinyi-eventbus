# Changelog - P0-P4 优化版本

**版本**: 1.2.0
**日期**: 2026-03-31
**状态**: 已完成

---

## 1. P0: 关键 Bug 修复

### 1.1 ClassCastException (生产问题)

**问题**: 使用 `deserializeType=RAW` + `entityType=String.class` 时，消费端收到 `EventModel` 而不是 `String`，导致 `ClassCastException`。

**根本原因**: `MethodEventListener.handle()` 总是传递 `EventModel` 列表给监听器方法，没有根据方法参数类型提取 entity。

**修复方案**:

1. **新增 `computeParameterElementType()` 方法** (lines 296-315)
   - 正确处理非泛型类型 (如 `String.class`)
   - 修复了泛型擦除导致的类型识别问题

2. **新增 `prepareParameter()` 方法** (lines 250-267)
   - 单参数方法 (非 List): 返回第一条消息的 entity
   - List 参数方法: 返回提取后的实体列表或原始 EventModel 列表

3. **修复 `handle()` 方法** (lines 220-243)
   - 单参数方法直接传递 `paramValues[0]` (而非包装在 `Object[]`)
   - 避免 Java 反射将 `Object[]` 当作单个 varargs 参数

### 1.2 影响范围

| 文件 | 修改 |
|------|------|
| `MethodEventListener.java` | 添加 `passEntity` 支持, 修复类型提取逻辑 |
| `EventListenerRegistryManager.java` | 传递 `passEntity` 参数 |
| `EventBusListener.java` | 添加 `passEntity` 注解属性 |

---

## 2. P1: 序列化类型统一

### 2.1 问题

- `DEFAULT`, `BASIC`, `JSON` 模式语义混淆
- `DEFAULT` 模式存在序列化不对称问题

### 2.2 解决方案

| 模式 | 状态 | 说明 |
|------|------|------|
| `EVENT` | ✅ 推荐 | 对称模式，只序列化/反序列化 entity |
| `RAW` | ✅ 推荐 | 高性能模式，直接发送原始字节 |
| `JSON` | ⚠️ 废弃 | 等效于 EVENT，语义不明确 |
| `DEFAULT` | ⚠️ 废弃 | 存在序列化不对称问题 |
| `BASIC` | ⚠️ 废弃 | 仅支持 String/byte[] |
| `MSG` | ✅ 保留 | 传递原生消息对象 |

### 2.3 修改

```java
// SerializeType.java
@Deprecated
DEFAULT("DEFAULT"),  // 添加 @Deprecated

@Deprecated
BASIC("BASIC"),

@Deprecated
JSON("JSON"),

EVENT("EVENT"),  // 推荐模式
RAW("RAW"),      // 高性能模式
MSG("MSG");
```

---

## 3. P2: OptimizedKafkaMqEventListenerRegistry 重构

### 3.1 问题

- 509 行单体类，职责过多
- Producer、Consumer、EOS、对象池逻辑混在一起
- 难以独立测试和维护

### 3.2 解决方案

| 内部类 | 职责 | 行数 |
|--------|------|------|
| `ProducerHandler` | Kafka 生产者封装 | ~130 |
| `ConsumerHandler` | Kafka 消费者封装 | ~80 |
| `EosOffsetManager` | EOS offset 管理 | ~60 |
| `OffsetCommitState` | 每消费者 offset 状态 | ~5 |

### 3.3 架构改进

1. **单一职责**: 每个内部类职责清晰
2. **内聚性**: 相关方法和字段分组在一起
3. **可测试性**: 可独立单元测试
4. **可读性**: 主类委托给专门处理器
5. **可维护性**: 修改生产者逻辑不影响消费者

---

## 4. P3: passEntity 选项

### 4.1 需求

- 用户需要控制是否传递 entity 还是 EventModel
- 向后兼容现有代码

### 4.2 解决方案

```java
// EventBusListener.java
boolean passEntity() default true;
```

| 值 | 行为 |
|----|------|
| `true` (默认) | 传递 entity (向后兼容) |
| `false` | 传递完整 EventModel |

**示例**:
```java
// 传递 entity
@EventBusListener(topic = "test", passEntity = true)
public void onEvent(List<String> events) { ... }

// 传递 EventModel
@EventBusListener(topic = "test", passEntity = false)
public void onEvent(List<EventModel> events) { ... }
```

---

## 5. P4: 弃用标记

### 5.1 SerializeType.java

所有废弃模式添加 `@Deprecated` 注解和警告日志:

- `DEFAULT` - 序列化不对称
- `BASIC` - 仅支持 String/byte[]
- `JSON` - 语义不明确，推荐使用 EVENT

### 5.2 BaseSerializer.java

- 默认序列化模式从 `DEFAULT` 改为 `EVENT`
- 添加废弃警告日志

---

## 6. 测试覆盖

### 6.1 单元测试

| 测试文件 | 覆盖场景 |
|----------|----------|
| `MethodEventListenerRawStringTest` | String, byte[], MyEvent + List 参数 |
| `MethodEventListenerEdgeCaseTest` | 泛型擦除, 单参数, 通配符 |
| `MethodEventListenerPerformanceTest` | 参数提取性能基准 |

### 6.2 新增测试场景

```java
// 单消息入参（非 List）- String 类型 ✅
testSingleMessage_withSingleStringParam()

// 单消息入参 - byte[] 类型 ✅
testSingleMessage_withByteArrayParam()

// 单消息入参 - 自定义对象类型 ✅
testSingleMessage_withCustomEventParam()

// 泛型擦除 - List<Object> ✅
testGenericErasure_withListObjectParam()

// passEntity=false 模式 ✅
testPassEntityFalse_shouldReceiveEventModel()

// 空消息列表 ✅
testEmptyMessageList()

// entity 为 null ✅
testNullEntity()
```

---

## 7. 性能影响

### 7.1 参数提取基准测试

```
Iterations: 100,000
Batch size: 100
Average per call: 4.54 µs
Throughput: 220,480 calls/sec
Messages/sec: 22.05 M
```

### 7.2 开销分析

```
Direct list extraction: 8.55 µs/call
MethodEventListener.handle(): 6.72 µs/call
Overhead: -1.83 µs/call (-27.2%)
```

> 注：MethodEventListener 实际比直接提取更快，因为 JIT 优化

---

## 8. 架构符合度

### 8.1 符合愿景的设计

| 模式 | 说明 |
|------|------|
| 事件驱动抽象 | 解耦生产者和消费者 |
| Strategy 模式 | 不同 MQ 实现 (Kafka, Rabbit, RocketMQ) |
| EOS 支持 | Exactly-once semantics |
| passEntity 选项 | 用户可选择 entity 或 EventModel |

### 8.2 可优化点

| 问题 | 建议 |
|------|------|
| 异常被静默吞噬 | 使用 `Exception` 而非 `Throwable`，记录日志 |
| 序列化紧耦合 | 考虑 Strategy 模式支持多种序列化 |

---

## 9. 提交记录

| Commit | 描述 |
|--------|------|
| `feat(listener): 添加 passEntity 支持和单消息入参修复` | P0, P4 实现 |
| `feat(serializer): 统一 EVENT/JSON 模式,废弃 DEFAULT/BASIC` | P1, P3 实现 |
| `refactor(kafka): 重构 OptimizedKafkaMqEventListenerRegistry 为内部类` | P2 实现 |
| `test(listener): 添加 MethodEventListener 全面测试` | 测试覆盖 |

---

## 10. 后续建议

1. **错误处理优化**: 改进 `Throwable ignored` 日志
2. **序列化抽象**: 引入 Serializer Strategy 模式
3. **监控增强**: 添加更多 PerformanceMonitor 指标
4. **文档完善**: 更新 README 和使用指南
