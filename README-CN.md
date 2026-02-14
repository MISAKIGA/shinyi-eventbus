# Shinyi EventBus

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.misakiga/shinyi-eventbus.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.misakiga%22%20AND%20a:%22shinyi-eventbus%22)

> **[English Documentation](README.md) | [中文文档](README-CN.md)**

一个轻量级、注解驱动的事件总线框架，专为 Spring Boot 应用设计。它提供了统一的接口来处理本地事件（Guava、Spring ApplicationContext）和分布式事件（RabbitMQ、RocketMQ、Kafka），简化了事件驱动架构的实现。

## 特性

- **统一 API**：无论底层传输方式如何，均使用统一的 `@EventBusListener` 注解发布和订阅事件。
- **混合支持**：无缝切换本地（内存中）和远程（消息队列）事件分发。
- **多 MQ 支持**：内置 RabbitMQ、RocketMQ 和 Kafka 支持。架构可扩展，易于集成其他 MQ。
- **上下文传播**：使用 TransmittableThreadLocal 自动传播上下文（如链路追踪 ID）。
- **灵活序列化**：支持多种序列化选项（JSON、原始字符串、字节数组、原生对象）。
- **异步执行**：内置线程池，支持异步事件处理。

## 支持的消息队列

| 消息队列 | 状态 | 说明 |
|----------|------|------|
| Guava EventBus | 已支持 | 本地内存事件总线 |
| Spring ApplicationEvent | 已支持 | Spring 框架内置事件 |
| RabbitMQ | 已支持 | 企业级消息代理 |
| RocketMQ | 已支持 | 分布式消息系统 |
| Kafka | 已支持 | 分布式事件流平台 |

## 快速开始

### 1. 添加依赖

构建项目并将其添加到你的 Spring Boot 应用的 `pom.xml` 中：

```xml
<dependency>
    <groupId>io.github.misakiga</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 启用 EventBus

在你的 Spring Boot 启动类上添加 `@EnableEventBus`：

```java
import com.shinyi.eventbus.anno.EnableEventBus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableEventBus
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置

在 `application.yml` 中配置事件总线。你可以根据需要启用本地或远程事件总线。

```yaml
shinyi:
  eventbus:
    # 异步监听器的线程池配置
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 1000
    
    # RabbitMQ 配置（可选）
    rabbit-mq:
      connect-configs:
        default-rabbit: # 此连接的 Bean 名称
          is-default: true # 设置为默认的 'rabbitmq' 总线
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
    # RocketMQ 配置（可选）
    rocket-mq:
      connect-configs:
        default-rocket:
          is-default: true
          namesrv-addr: localhost:9876
          group-name: my-producer-group

    # Kafka 配置（可选）
    kafka:
      connect-configs:
        default-kafka:
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
```

### 4. 发布事件

注入 `EventListenerRegistryManager` 来发布事件。

```java
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.support.EventListenerRegistryManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EventPublisher {

    @Autowired
    private EventListenerRegistryManager eventRegistryManager;

    public void sendEvent() {
        // 发布到本地 Guava 事件总线
        eventRegistryManager.publish("guava", EventModel.build("user.created", "User 123"));

        // 发布到 RabbitMQ（如果 is-default=true，使用 'rabbitmq' 即可）
        eventRegistryManager.publish("rabbitmq", EventModel.build("order.created", new OrderDTO()));

        // 发布到 Kafka
        eventRegistryManager.publish("kafka", EventModel.build("user.login", new LoginEvent()));
    }
}
```

### 5. 监听事件

使用 `@EventBusListener` 订阅事件。

```java
import com.shinyi.eventbus.anno.EventBusListener;
import com.shinyi.eventbus.EventModel;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

    /**
     * 监听本地事件 (Guava)
     */
    @EventBusListener(
        name = "guava", 
        topic = "user.created"
    )
    public void onUserCreated(EventModel<String> event) {
        System.out.println("收到用户创建事件: " + event.getEntity());
    }

    /**
     * 监听 RabbitMQ 事件
     * 根据配置自动绑定队列到交换机
     */
    @EventBusListener(
        name = "rabbitmq", 
        topic = "order.created",
        group = "order-service",
        exchange = "order.exchange",
        routingKey = "order.created"
    )
    public void onOrderCreated(EventModel<OrderDTO> event) {
        System.out.println("收到订单事件: " + event.getEntity());
    }

    /**
     * 监听 Kafka 事件
     */
    @EventBusListener(
        name = "kafka", 
        topic = "user.login",
        group = "login-service"
    )
    public void onUserLogin(EventModel<LoginEvent> event) {
        System.out.println("收到登录事件: " + event.getEntity());
    }
}
```

## Kafka 配置参考

以下是可用的 Kafka 配置属性：

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        my-kafka:
          # 是否为默认 Kafka 连接
          is-default: true
          
          # Kafka bootstrap servers（必填）
          bootstrap-servers: localhost:9092
          
          # 此连接的默认主题
          topic: my-topic
          
          # 消费者组 ID
          group-id: my-consumer-group
          
          # 客户端 ID
          client-id: my-client
          
          # 生产者设置
          acks: 1
          retries: 3
          batch-size: 16384
          linger-ms: 1
          buffer-memory: 33554432
          
          # 消费者设置
          auto-offset-reset: earliest
          enable-auto-commit: true
          auto-commit-interval-ms: 5000
          session-timeout-ms: 30000
          max-poll-records: 500
          
          # 序列化（默认使用 ByteArray 序列化器）
          key-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
          value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
```

## 文档

更多详细信息，请参阅 `doc/` 目录下的文档：

- [架构设计](doc/architecture-cn.md)
- [实现细节](doc/implementation-cn.md)
- [MQ 集成指南](doc/mq-integration-cn.md)
- [使用指南](doc/usage-guide-cn.md)
- [发布到 Maven 中央仓库指南](doc/maven-central-publish-guide.md)

## 许可证

[Apache License 2.0](LICENSE)
