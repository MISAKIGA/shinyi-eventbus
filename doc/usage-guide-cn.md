# 使用指南

## 快速开始

Shinyi EventBus 是一个轻量级库，简化了 Spring Boot 应用中的事件驱动架构。它提供了统一的方式来处理本地（内存）和分布式（基于 MQ）事件。

### 1. 安装

构建项目并安装到本地 Maven 仓库。

```bash
mvn clean install
```

将依赖添加到你的 `pom.xml`：

```xml
<dependency>
    <groupId>com.shinyi</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 启用 EventBus

在主应用类上添加 `@EnableEventBus` 注解。

```java
@SpringBootApplication
@EnableEventBus
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置

在 `application.yml` 或 `application.properties` 中配置事件总线。

```yaml
shinyi:
  eventbus:
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 1000
    
    # RabbitMQ（可选）
    rabbit-mq:
      connect-configs:
        default-rabbit:
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
    # RocketMQ（可选）
    rocket-mq:
      connect-configs:
        default-rocket:
          is-default: true
          namesrv-addr: localhost:9876
          group-name: producer-group
```

### 4. 发布事件

将 `EventListenerRegistryManager` 注入到你的服务中。

```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void triggerEvent() {
    // 发布到本地 Guava 事件总线
    eventListenerRegistryManager.publish("guava", EventModel.build("user.registered", "user123"));

    // 发布到 RabbitMQ（bean 名称 'default-rabbit' 或如果 is-default=true 则为 'rabbitmq'）
    eventListenerRegistryManager.publish("rabbitmq", EventModel.build("order.created", new OrderDTO()));
}
```

### 5. 监听事件

在任何 Spring Bean 方法上使用 `@EventBusListener` 注解。

```java
@Component
public class UserEventListener {

    // 监听本地 Guava 事件
    @EventBusListener(name = "guava", topic = "user.registered")
    public void onUserRegistered(EventModel<String> event) {
        System.out.println("用户注册: " + event.getEntity());
    }

    // 监听 RabbitMQ 事件
    @EventBusListener(
        name = "rabbitmq", 
        topic = "order.created",
        exchange = "order-exchange",
        routingKey = "order.created",
        queue = "order-queue"
    )
    public void onOrderCreated(EventModel<OrderDTO> event) {
        OrderDTO order = event.getEntity();
        System.out.println("订单创建: " + order.getId());
    }
}
```

### 序列化类型

你可以使用 `deserializeType` 控制事件负载的序列化/反序列化方式：

- `DEFAULT`：标准 JSON 对象映射。
- `BASIC`：String 或字节数组。
- `JSON`：JSON 字符串到对象映射，仅针对 `entity` 字段。
- `MSG`：原始消息对象（MQ 特定）。

```java
@EventBusListener(name = "rabbitmq", topic = "log", deserializeType = SerializeType.BASIC)
public void onLog(EventModel<String> event) {
    // event.getEntity() 是一个 String
}
```
