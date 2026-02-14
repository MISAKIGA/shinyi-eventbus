# 架构设计

## 概述

Shinyi EventBus 旨在解耦 Spring Boot 应用中的事件生产者和消费者，支持本地内存事件分发和远程消息队列集成。核心架构围绕统一的事件模型和基于注册表（Registry）的监听器管理系统展开。

## 核心概念

### 1. 统一事件模型 (`EventModel`)
所有事件，无论其来源（Guava、RabbitMQ、RocketMQ），都被封装在通用的 `EventModel<T>` 中。该模型包含：
- **Event ID**：用于追踪的唯一标识符。
- **Topic**：事件的主题。
- **Entity**：负载（泛型类型 `T`）。
- **Metadata**：分组、标签、DriveType（来源）。
- **Control Flags**：`enableAsync`、`serializeType`。

### 2. 事件监听器 (`EventListener`)
`EventListener` 接口定义了事件的处理方式。
- **MethodEventListener**：主要实现，封装了带有 `@EventBusListener` 注解的方法，处理反射调用和参数绑定。
- **ExecutableEventListener**：可执行监听器的扩展实现。

### 3. 注册表模式 (`EventListenerRegistry`)
为支持多种事件后端，系统采用注册表模式。
- `EventListenerRegistry<T>`：用于注册监听器和向特定后端发布事件的接口。
- **实现类**：
  - `GuavaEventListenerRegistry`：使用 Guava 的 `EventBus` 进行本地内存事件处理。
  - `SpringEventListenerRegistry`：使用 Spring 的 `ApplicationEventPublisher`。
  - `RabbitMqEventListenerRegistry`：封装 RabbitMQ 客户端进行 AMQP 消息传递。
  - `RocketMqEventListenerRegistry`：封装 RocketMQ 客户端。

### 4. 注册表管理器 (`EventListenerRegistryManager`)
核心协调器。
- 扫描带有 `@EventBusListener` 方法的 Bean。
- 根据注解的 `name` 属性，将注册委托给相应的 `EventListenerRegistry`。
- 提供统一的 `publish(String type, EventModel event)` 方法，将事件分发到正确的后端。

## 流程图

```mermaid
graph TD
    Publisher[发布者代码] -->|publish("rabbitmq", event)| Manager[EventListenerRegistryManager]
    Manager -->|根据类型路由| RabbitRegistry[RabbitMqEventListenerRegistry]
    Manager -->|根据类型路由| GuavaRegistry[GuavaEventListenerRegistry]
    
    RabbitRegistry -->|序列化并发送| RabbitMQ[(RabbitMQ Broker)]
    GuavaRegistry -->|发布到总线| GuavaBus[Guava EventBus]
    
    RabbitMQ -->|消费| RabbitListener[RabbitMQ 消费者]
    RabbitListener -->|反序列化并调用| MethodListener[MethodEventListener]
    
    GuavaBus -->|分发| MethodListener
```

## 异步执行

通过 `EventBusProperties` 配置全局线程池（`ThreadPoolTaskExecutor`），用于处理异步事件处理（如本地异步监听器）。远程 MQ 通常使用其自己的客户端线程，但如果配置了，也可以卸载到此线程池处理。

## 上下文传播

`EventBusContext` 使用 `TransmittableThreadLocal`（TTL）或 `InheritableThreadLocal` 传播上下文（如用户信息、追踪 ID），确保在事件处理过程中上下文得以保留。
