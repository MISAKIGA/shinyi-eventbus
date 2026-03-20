# 序列化模式详解

## 概述

Shinyi EventBus 提供多种序列化模式，用于控制事件如何在生产者和消费者之间序列化和反序列化。选择正确的模式对于确保生产者和消费者之间的对称性至关重要。

## 模式对比表

| 模式 | 序列化行为 | 反序列化行为 | 对称性 | 推荐场景 |
|------|-----------|-------------|--------|----------|
| **EVENT** | 只序列化 entity | 只反序列化 entity | ✅ 对称 | **推荐** - 跨 MQ 通用、新项目 |
| **JSON** | 只序列化 entity | 只反序列化 entity | ✅ 对称 | 与 EVENT 等效 |
| **RAW** | 只序列化 entity 原始字节 | 只反序列化 entity 原始字节 | ✅ 对称 | Kafka、Redis 等字节导向 MQ |
| **DEFAULT** | 序列化整个 EventModel JSON | 先尝试 EventModel，回退到 entity | ❌ **非对称** | 保持向后兼容 |
| **BASIC** | String.valueOf(EventModel) | 只反序列化 String/byte[] | ❌ **非对称** | 保持向后兼容 |
| **MSG** | 不进行序列化 | 返回空 entity | N/A | 框架原生消息对象 |

## 序列化对称性问题

### 问题说明

远程 MQ 注册中心（RabbitMQ、RocketMQ、Kafka、Redis）都依赖 `BaseSerializer` 进行序列化。以下是各模式的对称性分析：

```
序列化对称性矩阵：

模式      | serialize()                    | deserialize()              | 对称? |
----------|--------------------------------|---------------------------|-------|
EVENT     | 只序列化 entity                | 只反序列化 entity          | ✅    |
JSON      | 只序列化 entity                | 只反序列化 entity          | ✅    |
RAW       | 只序列化 entity 原始字节        | 只反序列化 entity 原始字节  | ✅    |
DEFAULT   | 序列化整个 EventModel JSON     | 先尝试 EventModel，回退     | ❌    |
BASIC     | String.valueOf(EventModel)    | 只反序列化 String/byte[]    | ❌    |
```

### 非对称场景（失败案例）

1. **Publisher 使用 DEFAULT，Consumer 使用 JSON**
   - Publisher 发送完整 EventModel JSON
   - Consumer 尝试只解析 entity 部分 → **失败**

2. **Publisher 使用 JSON，Consumer 使用 DEFAULT**
   - Publisher 只发送 entity JSON
   - Consumer 尝试解析为 EventModel → **失败**

## 推荐配置

### 新项目 / 跨 MQ 通信

```java
@EventBusListener(
    name = "kafka",
    topic = "my-topic",
    entityType = MyEvent.class,
    deserializeType = SerializeType.EVENT  // 推荐：对称模式
)
public void handleMyEvent(EventModel<MyEvent> event) {
    // ...
}
```

### Kafka / Redis 高性能场景

```java
@EventBusListener(
    name = "kafka",
    topic = "my-topic",
    entityType = byte[].class,
    deserializeType = SerializeType.RAW  // 高性能字节模式
)
public void handleMyEvent(EventModel<byte[]> event) {
    // ...
}
```

### 与 Spring 本地事件配合

```java
@EventBusListener(
    name = "spring",
    topic = "my-topic",
    entityType = MyEvent.class,
    deserializeType = SerializeType.JSON
)
public void handleMyEvent(EventModel<MyEvent> event) {
    // ...
}
```

## 废弃模式

以下模式存在序列化不对称问题，已标记为 `@Deprecated`：

- **DEFAULT**: 序列化整个 EventModel JSON，但反序列化行为不一致
- **BASIC**: 仅支持简单类型 String/byte[]，不适用于复杂对象

建议迁移到 **EVENT** 或 **JSON** 模式。

## 实现细节

所有 MQ 注册中心（RabbitMQ、RocketMQ、Kafka、Redis）都使用统一的 `BaseSerializer`：

```java
// SerializeType.java 中的模式定义
EVENT("EVENT"),   // 推荐的对称模式
DEFAULT("DEFAULT"), // @Deprecated
BASIC("BASIC"),   // @Deprecated
JSON("JSON"),
MSG("MSG"),
RAW("RAW");
```

序列化逻辑在 `BaseSerializer.java` 中统一处理，确保所有 MQ 类型的行为一致。
