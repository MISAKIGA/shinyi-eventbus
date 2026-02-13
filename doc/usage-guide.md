# Usage Guide

## Getting Started

Shinyi EventBus is a lightweight library that simplifies event-driven architecture in Spring Boot applications. It provides a unified way to handle local (in-memory) and distributed (MQ-based) events.

### 1. Installation

Build the project and install it to your local Maven repository.

```bash
mvn clean install
```

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.shinyi</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Enable EventBus

Annotate your main application class with `@EnableEventBus`.

```java
@SpringBootApplication
@EnableEventBus
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Configuration

Configure the event bus in `application.yml` or `application.properties`.

```yaml
shinyi:
  eventbus:
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 1000
    
    # RabbitMQ (Optional)
    rabbit-mq:
      connect-configs:
        default-rabbit:
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
    # RocketMQ (Optional)
    rocket-mq:
      connect-configs:
        default-rocket:
          is-default: true
          namesrv-addr: localhost:9876
          group-name: producer-group
```

### 4. Publishing Events

Inject `EventListenerRegistryManager` into your service.

```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void triggerEvent() {
    // Publish to local Guava event bus
    eventListenerRegistryManager.publish("guava", EventModel.build("user.registered", "user123"));

    // Publish to RabbitMQ (bean name 'default-rabbit' or 'rabbitmq' if is-default=true)
    eventListenerRegistryManager.publish("rabbitmq", EventModel.build("order.created", new OrderDTO()));
}
```

### 5. Listening to Events

Use the `@EventBusListener` annotation on any Spring bean method.

```java
@Component
public class UserEventListener {

    // Listen to local Guava events
    @EventBusListener(name = "guava", topic = "user.registered")
    public void onUserRegistered(EventModel<String> event) {
        System.out.println("User registered: " + event.getEntity());
    }

    // Listen to RabbitMQ events
    @EventBusListener(
        name = "rabbitmq", 
        topic = "order.created",
        exchange = "order-exchange",
        routingKey = "order.created",
        queue = "order-queue"
    )
    public void onOrderCreated(EventModel<OrderDTO> event) {
        OrderDTO order = event.getEntity();
        System.out.println("Order created: " + order.getId());
    }
}
```

### Serialization Types

You can control how the event payload is serialized/deserialized using `deserializeType`:

- `DEFAULT`: Standard JSON object mapping.
- `BASIC`: String or byte array.
- `JSON`: JSON string to object mapping for `entity` field.
- `MSG`: Raw message object (MQ specific).

```java
@EventBusListener(name = "rabbitmq", topic = "log", deserializeType = SerializeType.BASIC)
public void onLog(EventModel<String> event) {
    // event.getEntity() is a String
}
```
