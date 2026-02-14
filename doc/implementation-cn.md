# 实现细节

## 项目结构

项目采用 Maven 结构，职责划分清晰：

- `com.shinyi.eventbus`
  - `anno`：注解（`@EventBusListener`、`@EnableEventBus`）
  - `config`：Spring 配置和属性类
  - `exception`：自定义异常（`EventBusException`）
  - `listener`：监听器基础实现（`MethodEventListener`）
  - `registry`：不同事件总线类型的实现（`Guava`、`Spring`、`RabbitMQ`、`RocketMQ`）
  - `serialize`：序列化工具（`Serializer`、`SerializeType`）
  - `support`：核心逻辑（`EventListenerRegistryManager`）
  - `util`：辅助工具（`JsonUtils`）

## 核心类

### 1. `EventListenerRegistryManager` (`support/EventListenerRegistryManager.java`)
这是库的核心，实现了 `SmartLifecycle` 以便钩入 Spring 应用生命周期。
- **扫描**：使用 `MethodIntrospector` 和 `AnnotatedElementUtils` 查找带有 `@EventBusListener` 的方法。
- **过滤**：跳过 `@Lazy` Bean 以避免不必要的初始化。
- **注册**：创建 `MethodEventListener` 实例并将其注册到相应的 `EventListenerRegistry`。
- **发布**：根据类型字符串将事件分发到正确的注册表。

### 2. `MethodEventListener` (`listener/MethodEventListener.java`)
封装了反射性的 `Method` 调用。处理：
- 检查事件是否匹配预期类型。
- 如需要，反序列化负载（`deserializeType`）。
- 调用目标 Bean 上的目标方法。
- 参数绑定。

### 3. `EventListenerRegistry` 实现类 (`registry/*.java`)
每个实现适配特定的消息系统：
- **`GuavaEventListenerRegistry`**：封装 Guava 的 `EventBus`。直接注册监听器。发布的事件发送到总线。
- **`RabbitMqEventListenerRegistry`**：使用 RabbitMQ Java 客户端。根据监听器注解创建队列、交换机和绑定。发布的事件发送到交换机。
- **`RocketMqEventListenerRegistry`**：使用 RocketMQ/阿里云 ONS 客户端。订阅主题和标签。发布的事件通过生产者发送。

### 4. 配置 (`config/*.java`)
配置通过 `@ConfigurationProperties(prefix = "shinyi.eventbus")` 加载。
- **`EventBusProperties`**：全局设置，如线程池大小。
- **`RabbitConfig`**：按连接名称键控的 `RabbitMqConnectConfig` 映射。
- **`RocketConfig`**：按连接名称键控的 `RocketMqConnectConfig` 映射。

### 5. 序列化 (`serialize/*.java`)
库支持 `SerializeType` 中定义的灵活序列化策略：
- **DEFAULT**：整个 `EventModel` 的 JSON 序列化。
- **BASIC**：简单类型（String、byte[]）。
- **JSON**：仅对 `entity` 字段进行 JSON 序列化。
- **MSG**：原始消息对象（MQ 特定）。

### 6. 错误处理
全局错误处理逻辑可自定义，但目前使用 `SimpleErrorHandler` 或抛出 `EventBusException`。异步错误被记录，同步错误传播给调用者。
