# MQ 集成指南

本指南详细介绍如何在 Shinyi EventBus 中使用各个支持的消息队列（MQ）。

## 支持的消息队列

Shinyi EventBus 内置支持以下消息队列：

| 消息队列 | 包名 | 状态 |
|----------|------|------|
| Guava EventBus | com.shinyi.eventbus.registry.GuavaEventListenerRegistry | 内置 |
| Spring ApplicationEvent | com.shinyi.eventbus.registry.SpringEventListenerRegistry | 内置 |
| RabbitMQ | com.shinyi.eventbus.config.rabbit | 内置 |
| RocketMQ | com.shinyi.eventbus.config.rocketmq | 内置 |
| Kafka | com.shinyi.eventbus.config.kafka | 内置 |

## RabbitMQ 集成

### 配置

```yaml
shinyi:
  eventbus:
    rabbit-mq:
      connect-configs:
        default-rabbit:
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          # 生产者设置
          publisher-confirms: true
          publisher-retry: true
          # 消费者设置
          concurrent-consumers: 3
          max-concurrent-consumers: 10
          prefetch-count: 250
```

### 使用示例

```java
@EventBusListener(
    name = "rabbitmq",
    topic = "order.created",
    group = "order-service",
    exchange = "order.exchange",
    routingKey = "order.created",
    queue = "order.queue",
    exchangeType = "topic"
)
public void onOrderCreated(EventModel<OrderDTO> event) {
    // 处理事件
}
```

## RocketMQ 集成

### 配置

```yaml
shinyi:
  eventbus:
    rocket-mq:
      connect-configs:
        default-rocket:
          is-default: true
          namesrv-addr: localhost:9876
          group-name: my-producer-group
          # 生产者设置
          send-message-timeout: 3000
          compress-message-body-threshold: 4096
          max-message-size: 4194304
          # 消费者设置
          consume-thread-min: 20
          consume-thread-max: 64
          consume-message-batch-max-size: 1
```

### 使用示例

```java
@EventBusListener(
    name = "rocketmq",
    topic = "user.registered",
    group = "user-service",
    tags = "register,notification"
)
public void onUserRegistered(EventModel<UserDTO> event) {
    // 处理事件
}
```

## Kafka 集成

### 配置

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
          
          # 生产者设置
          acks: 1
          retries: 3
          batch-size: 16384
          linger-ms: 1
          buffer-memory: 33554432
          max-in-flight-requests-per-connection: 5
          
          # 消费者设置
          auto-offset-reset: earliest
          enable-auto-commit: true
          auto-commit-interval-ms: 5000
          session-timeout-ms: 30000
          max-poll-records: 500
          max-poll-interval-ms: 300000
          
          # 高级设置
          client-id: my-client-id
          receive-buffer-bytes: 65536
          send-buffer-bytes: 131072
```

### 使用示例

```java
@EventBusListener(
    name = "kafka",
    topic = "user.login",
    group = "login-service"
)
public void onUserLogin(EventModel<LoginEvent> event) {
    System.out.println("收到登录事件: " + event.getEntity());
}
```

### 发布 Kafka 事件

```java
@Service
public class EventPublisher {

    @Autowired
    private EventListenerRegistryManager eventRegistryManager;

    public void publishLoginEvent(LoginEvent loginEvent) {
        // 同步发布
        eventRegistryManager.publish("kafka", 
            EventModel.build("user.login", loginEvent, false));
        
        // 异步发布
        eventRegistryManager.publish("kafka", 
            EventModel.build("user.login", loginEvent, true));
    }
}
```

### 序列化选项

Kafka 支持通过 `@EventBusListener` 注解配置多种序列化类型：

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

### Kafka Kerberos (GSSAPI) 认证

Shinyi EventBus 支持通过 GSSAPI SASL 机制进行 Kerberos 认证。

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        kerberos-kafka:
          is-default: true
          bootstrap-servers: kafka.example.com:9092
          topic: my-topic
          group-id: my-consumer-group

          # Kerberos 安全设置
          security-protocol: SASL_SSL
          sasl-mechanism: GSSAPI

          # Kerberos 认证
          kerberos-service-name: kafka
          kerberos-principal: kafka/kafka.example.com@EXAMPLE.COM
          kerberos-keytab: /etc/kafka/kafka.keytab
          kerberos-krb5-location: /etc/kafka/krb5.conf
```

#### 配置参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| security-protocol | String | PLAINTEXT | 安全协议 (PLAINTEXT, SASL_PLAINTEXT, SASL_SSL) |
| sasl-mechanism | String | PLAIN | SASL 机制 (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI) |
| kerberos-service-name | String | kafka | Kerberos 服务名称 |
| kerberos-principal | String | - | Kerberos 主体 (如 kafka/kafka.example.com@EXAMPLE.COM) |
| kerberos-keytab | String | - | Kerberos keytab 文件路径 |
| kerberos-krb5-location | String | - | krb5.conf 文件路径 |

#### krb5.conf 配置示例

```ini
[libdefaults]
    default_realm = EXAMPLE.COM
    dns_lookup_realm = false
    dns_lookup_kdc = false
    ticket_lifetime = 24h
    renew_until = 7d

[realms]
    EXAMPLE.COM = {
        kdc = kdc.example.com
        admin_server = kdc.example.com
    }

[domain_realm]
    .example.com = EXAMPLE.COM
    example.com = EXAMPLE.COM
```

## 本地事件总线（Guava/Spring）

### Guava EventBus 配置

```yaml
shinyi:
  eventbus:
    # 本地事件总线使用线程池配置
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 10000
```

### 使用示例

```java
@EventBusListener(name = "guava", topic = "user.created")
public void onUserCreated(EventModel<String> event) {
    System.out.println("本地事件: " + event.getEntity());
}
```

### Spring ApplicationEvent 使用

```java
@EventBusListener(name = "spring", topic = "spring.event")
public void onSpringEvent(EventModel<Object> event) {
    // 处理 Spring 应用程序事件
}
```

## 扩展自定义 MQ 提供商

Shinyi EventBus 使用基于注册表的系统，可以轻松添加对其他消息系统的支持。

### 步骤 1：实现 EventListenerRegistry

创建实现 `EventListenerRegistry<EventModel<?>>` 的新类。

```java
public class CustomMqEventListenerRegistry implements EventListenerRegistry<EventModel<?>> {

    private final String name;
    private final CustomMqConfig config;

    public CustomMqEventListenerRegistry(String name, CustomMqConfig config) {
        this.name = name;
        this.config = config;
    }

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.CUSTOM_MQ;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<EventModel<?>>> listeners) {
        // 根据监听器初始化消费者
    }

    @Override
    public void publish(EventModel<?> event) {
        // 发布到自定义 MQ
    }

    @Override
    public void close() {
        // 清理资源
    }
}
```

### 步骤 2：创建配置属性

```java
@ConfigurationProperties(prefix = "shinyi.eventbus.custom-mq")
public class CustomMqConfig {
    private Map<String, CustomMqConnectConfig> connectConfigs;
}
```

### 步骤 3：创建自动配置

```java
@Configuration
@ConditionalOnBean(CustomMqConfig.class)
public class CustomMqAutoConfiguration {

    @Autowired
    private ApplicationContext applicationContext;

    public void registerBeanDefinitions() {
        // 注册 CustomMqEventListenerRegistry beans
    }
}
```
