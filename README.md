# Shinyi EventBus

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

> **[English Documentation](README.md) | [中文文档](README-CN.md)**

A lightweight, annotation-driven event bus framework designed for Spring Boot applications. It provides a unified interface for handling both local events (Guava, Spring ApplicationContext) and distributed events (RabbitMQ, RocketMQ), simplifying event-driven architecture implementation.

## Features

- **Unified API**: Publish and subscribe to events using the same `@EventBusListener` annotation, regardless of the underlying transport.
- **Hybrid Support**: Seamlessly switch between local (in-memory) and remote (message queue) event distribution.
- **Multi-MQ Support**: Built-in support for RabbitMQ and RocketMQ. extensible architecture for other MQs (e.g., Kafka).
- **Context Propagation**: Automatic context propagation (e.g., trace IDs) using TransmittableThreadLocal.
- **Serialization Control**: Flexible serialization options (JSON, Raw String, Byte Array, Native Object).
- **Async Execution**: Built-in thread pool for asynchronous event processing.

## Quick Start

### 1. Add Dependency

Build the project and add it to your Spring Boot application's `pom.xml`:

```xml
<dependency>
    <groupId>com.shinyi</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Enable EventBus

Add `@EnableEventBus` to your Spring Boot application class:

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

### 3. Configuration

Configure the event bus in `application.yml`. You can enable local or remote event buses as needed.

```yaml
shinyi:
  eventbus:
    # Thread pool configuration for async listeners
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 1000
    
    # RabbitMQ Configuration (Optional)
    rabbit-mq:
      connect-configs:
        default-rabbit: # Bean name for this connection
          is-default: true # Sets this as the default 'rabbitmq' bus
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
    # RocketMQ Configuration (Optional)
    rocket-mq:
      connect-configs:
        default-rocket:
          is-default: true
          namesrv-addr: localhost:9876
          group-name: my-producer-group
```

### 4. Publishing Events

Inject `EventListenerRegistryManager` to publish events.

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
        // Publish to local Guava event bus
        eventRegistryManager.publish("guava", EventModel.build("user.created", "User 123"));

        // Publish to RabbitMQ (using default 'rabbitmq' name if is-default=true)
        eventRegistryManager.publish("rabbitmq", EventModel.build("order.created", new OrderDTO()));
    }
}
```

### 5. Listening to Events

Use `@EventBusListener` to subscribe to events.

```java
import com.shinyi.eventbus.anno.EventBusListener;
import com.shinyi.eventbus.EventModel;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

    /**
     * Listen to local events (Guava)
     */
    @EventBusListener(
        name = "guava", 
        topic = "user.created"
    )
    public void onUserCreated(EventModel<String> event) {
        System.out.println("Received user created event: " + event.getEntity());
    }

    /**
     * Listen to RabbitMQ events
     * Automatically binds queue to exchange based on config
     */
    @EventBusListener(
        name = "rabbitmq", 
        topic = "order.created",
        group = "order-service",
        exchange = "order.exchange",
        routingKey = "order.created"
    )
    public void onOrderCreated(EventModel<OrderDTO> event) {
        System.out.println("Received order event: " + event.getEntity());
    }
}
```

## Documentation

For more detailed information, please refer to the documentation in the `doc/` directory:

- [Architecture Design](doc/architecture.md)
- [Implementation Details](doc/implementation.md)
- [MQ Integration Guide](doc/mq-integration.md)
- [Usage Guide](doc/usage-guide.md)

## License

Apache License 2.0
