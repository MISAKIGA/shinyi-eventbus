# 使用指南

## 概述

Shinyi EventBus 是一个轻量级、注解驱动的事件总线框架，专为 Spring Boot 应用设计。它提供了统一的接口来处理本地事件（Guava、Spring ApplicationContext）和分布式事件（RabbitMQ、RocketMQ、Kafka、Redis），简化了事件驱动架构的实现。

## 支持的消息队列

| 消息队列 | 类型 | 配置前缀 |
|----------|------|----------|
| Guava EventBus | 本地 | 内置 |
| Spring ApplicationEvent | 本地 | 内置 |
| RabbitMQ | 远程 | shinyi.eventbus.rabbit-mq |
| RocketMQ | 远程 | shinyi.eventbus.rocket-mq |
| Kafka | 远程 | shinyi.eventbus.kafka |
| Redis | 远程 | shinyi.eventbus.redis |

## 安装

构建项目并安装到本地 Maven 仓库：

```bash
mvn clean install
```

在 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>io.github.misakiga</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 启用 EventBus

在主应用类上添加 `@EnableEventBus` 注解：

```java
@SpringBootApplication
@EnableEventBus
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## 配置

### 线程池配置

所有事件类型共享相同的线程池配置：

```yaml
shinyi:
  eventbus:
    thread-pool-core-size: 4        # 核心线程池大小
    thread-pool-max-size: 8        # 最大线程池大小
    thread-name-prefix: "eventbus-" # 线程名称前缀
    max-queue-size: 10000          # 最大队列大小
    await-termination-seconds: 60   # 等待终止秒数
```

---

## Guava EventBus

### 描述
Guava EventBus 是一个本地内存事件总线，适用于单实例应用或应用内事件通信。

### 配置
无需额外配置，始终启用。

### 使用示例

**发布事件：**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishUserCreatedEvent(String userId) {
    eventListenerRegistryManager.publish("guava", 
        EventModel.build("user.created", userId));
}
```

**监听事件：**
```java
@EventBusListener(name = "guava", topic = "user.created")
public void onUserCreated(EventModel<String> event) {
    System.out.println("用户已创建: " + event.getEntity());
}
```

---

## Spring ApplicationEvent

### 描述
使用 Spring 内置的 ApplicationEvent 系统进行 Spring 上下文内的事件发布和监听。

### 配置
无需额外配置，始终启用。

### 使用示例

**发布事件：**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishSpringEvent(Object data) {
    eventListenerRegistryManager.publish("spring", 
        EventModel.build("spring.event", data));
}
```

**监听事件：**
```java
@EventBusListener(name = "spring", topic = "spring.event")
public void onSpringEvent(EventModel<Object> event) {
    System.out.println("收到 Spring 事件: " + event.getEntity());
}
```

---

## RabbitMQ

### 描述
企业级消息代理，支持复杂路由、多种交换机类型和可靠的消息传递。

### 配置参数

```yaml
shinyi:
  eventbus:
    rabbit-mq:
      connect-configs:
        default-rabbit:
          # 基础设置
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
          # 交换机设置
          exchange: my-exchange
          exchange-type: topic    # direct, fanout, topic, headers
          
          # 队列设置
          queue: my-queue
          routing-key: my.routing.key
          
          # 生产者设置
          publisher-confirms: true
          publisher-retry: true
          send-msg-timeout: 3000
          
          # 消费者设置
          concurrent-consumers: 3
          max-concurrent-consumers: 10
          prefetch-count: 250
          consumer-timeout-millis: 10000
          
          # 消息设置
          durable: true
          auto-delete: false
          skip-create-producer: false
```

### 配置参考

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| host | String | localhost | RabbitMQ 服务器地址 |
| port | int | 5672 | RabbitMQ 服务器端口 |
| username | String | guest | 用户名 |
| password | String | guest | 密码 |
| virtual-host | String | / | 虚拟主机 |
| exchange | String | - | 交换机名称 |
| exchange-type | String | direct | 交换机类型 |
| queue | String | - | 队列名称 |
| routing-key | String | - | 路由键 |
| durable | boolean | true | 队列持久化 |
| auto-delete | boolean | false | 自动删除队列 |
| concurrent-consumers | int | 1 | 并发消费者数量 |
| prefetch-count | int | 250 | 预取数量 |

### 使用示例

**发布事件：**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishOrderEvent(OrderDTO order) {
    // 同步发布
    eventListenerRegistryManager.publish("rabbitmq", 
        EventModel.build("order.created", order));
    
    // 异步发布
    eventListenerRegistryManager.publish("rabbitmq", 
        EventModel.build("order.created", order, true));
}
```

**监听事件：**
```java
@EventBusListener(
    name = "rabbitmq",
    topic = "order.created",
    group = "order-service",
    exchange = "order.exchange",
    routingKey = "order.created",
    queue = "order.queue"
)
public void onOrderCreated(EventModel<OrderDTO> event) {
    OrderDTO order = event.getEntity();
    System.out.println("订单已创建: " + order.getId());
}
```

---

## RocketMQ

### 描述
阿里巴巴分布式消息系统，支持顺序消息、事务消息和延迟消息。

### 配置参数

```yaml
shinyi:
  eventbus:
    rocket-mq:
      connect-configs:
        default-rocket:
          # 基础设置
          is-default: true
          namesrv-addr: localhost:9876
          group-name: producer-group
          
          # 生产者设置
          send-message-timeout: 3000
          compress-message-body-threshold: 4096
          max-message-size: 4194304
          
          # 消费者设置
          consume-thread-min: 20
          consume-thread-max: 64
          consume-message-batch-max-size: 1
```

### 配置参考

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| namesrv-addr | String | - | NameServer 地址 |
| group-name | String | - | 生产者/消费者组名 |
| send-message-timeout | int | 3000 | 发送超时时间（毫秒） |
| compress-message-body-threshold | int | 4096 | 消息压缩阈值 |
| max-message-size | int | 4194304 | 最大消息大小 |
| consume-thread-min | int | 10 | 最小消费者线程数 |
| consume-thread-max | int | 64 | 最大消费者线程数 |

### 使用示例

**发布事件：**
```java
@EventBusListener(name = "rocketmq", topic = "user.registered")
public void onUserRegistered(EventModel<UserDTO> event) {
    System.out.println("用户已注册: " + event.getEntity());
}
```

**监听事件：**
```java
@EventBusListener(
    name = "rocketmq",
    topic = "user.registered",
    group = "user-service",
    tags = "register,notification"
)
public void onUserRegistered(EventModel<UserDTO> event) {
    UserDTO user = event.getEntity();
    // 处理用户注册
}
```

---

## Kafka

### 描述
分布式事件流平台，适用于高吞吐量、实时数据管道和事件驱动的微服务。

### 配置参数

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          # 基础设置
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
          
          # 生产者设置
          acks: 1                    # 0, 1, all
          retries: 3
          batch-size: 16384
          linger-ms: 1
          buffer-memory: 33554432
          max-in-flight-requests-per-connection: 5
          
          # 消费者设置
          auto-offset-reset: earliest  # earliest, latest, none
          enable-auto-commit: true
          auto-commit-interval-ms: 5000
          session-timeout-ms: 30000
          max-poll-records: 500
          max-poll-interval-ms: 300000
          
          # 高级设置
          client-id: my-client-id
          receive-buffer-bytes: 65536
          send-buffer-bytes: 131072
          
          # 序列化（自动配置）
          key-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
          value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
```

### 配置参考

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| bootstrap-servers | String | - | Kafka 集群地址（逗号分隔） |
| topic | String | - | 默认主题 |
| group-id | String | - | 消费者组 ID |
| acks | String | 1 | 确认级别 |
| retries | int | 3 | 重试次数 |
| batch-size | int | 16384 | 批次大小（字节） |
| linger-ms | int | 1 | 批次等待时间（毫秒） |
| buffer-memory | int | 33554432 | 缓冲区大小（字节） |
| auto-offset-reset | String | earliest | 重置偏移量策略 |
| enable-auto-commit | boolean | true | 自动提交启用 |
| session-timeout-ms | int | 30000 | 会话超时时间（毫秒） |
| max-poll-records | int | 500 | 每次轮询最大记录数 |

### 使用示例

**发布事件：**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishUserLoginEvent(LoginEvent event) {
    // 同步发布
    eventListenerRegistryManager.publish("kafka", 
        EventModel.build("user.login", event, false));
    
    // 异步发布
    eventListenerRegistryManager.publish("kafka", 
        EventModel.build("user.login", event, true));
}
```

**监听事件：**
```java
@EventBusListener(
    name = "kafka",
    topic = "user.login",
    group = "login-service"
)
public void onUserLogin(EventModel<LoginEvent> event) {
    LoginEvent login = event.getEntity();
    System.out.println("用户登录: " + login.getUserId());
}
```

**序列化选项：**
```java
// 默认 JSON 序列化
@EventBusListener(name = "kafka", topic = "event.json", serializeType = "DEFAULT")
public void onJsonEvent(EventModel<MyEvent> event) { }

// 原始消息（不进行反序列化）
@EventBusListener(name = "kafka", topic = "event.raw", serializeType = "MSG")
public void onRawEvent(EventModel<?> event) { 
    byte[] rawData = event.getRawData();
}
```

---

## Redis

### 描述
基于 Redis 的事件总线，支持 Pub/Sub 实时消息和 Stream 持久化消息（带消费者组）。

### 配置参数

```yaml
shinyi:
  eventbus:
    redis:
      connect-configs:
        default-redis:
          # 基础设置
          is-default: true
          host: localhost
          port: 6379
          password:              # 可选
          database: 0
          
          # 连接设置
          connection-timeout: 2000
          socket-timeout: 2000
          
          # Pub/Sub 设置
          channel-prefix: "eventbus:"
          
          # Stream 设置
          use-stream: false          # 使用 Stream 替代 Pub/Sub
          stream-key: my-stream       # Stream 键（如果 use-stream=true）
          group: my-group             # 消费者组（如果 use-stream=true）
```

### 配置参考

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| host | String | localhost | Redis 服务器地址 |
| port | int | 6379 | Redis 服务器端口 |
| password | String | - | 密码（可选） |
| database | int | 0 | 数据库索引 |
| connection-timeout | int | 2000 | 连接超时时间（毫秒） |
| socket-timeout | int | 2000 | Socket 超时时间（毫秒） |
| channel-prefix | String | eventbus: | Pub/Sub 通道前缀 |
| use-stream | boolean | false | 使用 Redis Stream 替代 Pub/Sub |
| stream-key | String | - | Stream 键 |
| group | String | - | 消费者组名称 |

### 使用示例

**发布事件（Pub/Sub）：**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishRedisEvent(DataEvent event) {
    eventListenerRegistryManager.publish("redis", 
        EventModel.build("user.action", event));
}
```

**监听事件（Pub/Sub）：**
```java
@EventBusListener(
    name = "redis",
    topic = "user.action",
    group = "action-processor"
)
public void onUserAction(EventModel<DataEvent> event) {
    DataEvent data = event.getEntity();
    System.out.println("用户操作: " + data.getType());
}
```

**使用 Redis Stream：**
```yaml
shinyi:
  eventbus:
    redis:
      connect-configs:
        stream-redis:
          is-default: true
          host: localhost
          use-stream: true
          stream-key: eventbus-stream
          group: eventbus-group
```

```java
@EventBusListener(
    name = "redis",
    topic = "event.stream",
    group = "stream-consumer"
)
public void onStreamEvent(EventModel<DataEvent> event) {
    // 处理 Stream 事件
}
```

---

## 事件模型

### 创建事件

```java
// 简单字符串事件
EventModel<String> event1 = EventModel.build("topic.name", "message");

// 对象事件
EventModel<OrderDTO> event2 = EventModel.build("order.created", orderDTO);

// 带异步标志
EventModel<EventData> event3 = EventModel.build("topic.name", data, true); // 异步
```

### 事件字段

| 字段 | 类型 | 说明 |
|------|------|------|
| eventId | String | 唯一事件 ID |
| topic | String | 事件主题 |
| entity | Object | 事件载荷 |
| serializeType | String | 序列化类型 |
| group | String | 消费者组 |
| isAsync | boolean | 异步发布标志 |
| rawData | byte[] | 原始消息数据 |
| driveType | String | 事件总线类型 |

---

## 序列化类型

| 类型 | 说明 |
|------|------|
| DEFAULT | 标准 JSON 序列化 |
| JSON | JSON 到对象映射 |
| BASIC | 字符串或字节数组 |
| MSG | 原始消息（MQ 特定） |

```java
@EventBusListener(name = "kafka", topic = "log", serializeType = "BASIC")
public void onLog(EventModel<String> event) {
    String log = event.getEntity();
}
```

---

## 最佳实践

1. **事件设计**：使用有意义的主题名称（如 `order.created`、`user.login`）
2. **错误处理**：在监听器中实现适当的错误处理
3. **幂等性**：在可能的情况下设计幂等事件
4. **监控**：为事件处理添加日志和监控
5. **线程安全**：在监听器之间共享状态时注意线程安全

---

## 高级主题

### 多个事件总线实例

可以配置多个相同 MQ 类型的不同实例：

```yaml
shinyi:
  eventbus:
    rabbit-mq:
      connect-configs:
        order-rabbit:
          is-default: false
          host: order-rabbit-server
          exchange: order-exchange
        payment-rabbit:
          is-default: false
          host: payment-rabbit-server  
          exchange: payment-exchange
```

### 上下文传播

事件自动传播 MDC（映射诊断上下文）和 ThreadLocal 值：

```java
MDC.put("traceId", "abc123");
eventListenerRegistryManager.publish("kafka", EventModel.build("event", data));
```

### 事件回调

对于异步发布，可以在成功或失败时接收回调：

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onSuccess(EventResult result) {
        System.out.println("消息已发送: " + result.getMessageId());
    }
    
    @Override
    public void onFailure(EventResult result, Throwable throwable) {
        System.out.println("消息失败: " + throwable.getMessage());
    }
};

EventModel<EventData> event = EventModel.build("topic", data, true);
event.setEventCallback(callback);
eventListenerRegistryManager.publish("kafka", event);
```
