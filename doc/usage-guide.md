# Usage Guide

## Overview

Shinyi EventBus is a lightweight, annotation-driven event bus framework designed for Spring Boot applications. It provides a unified interface for handling both local events (Guava, Spring ApplicationContext) and distributed events (RabbitMQ, RocketMQ, Kafka, Redis), simplifying event-driven architecture implementation.

## Supported Message Queues

| Message Queue | Type | Configuration Prefix |
|---------------|------|---------------------|
| Guava EventBus | Local | Built-in |
| Spring ApplicationEvent | Local | Built-in |
| RabbitMQ | Remote | shinyi.eventbus.rabbit-mq |
| RocketMQ | Remote | shinyi.eventbus.rocket-mq |
| Kafka | Remote | shinyi.eventbus.kafka |
| Redis | Remote | shinyi.eventbus.redis |

## Installation

Build the project and install it to your local Maven repository.

```bash
mvn clean install
```

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.misakiga</groupId>
    <artifactId>shinyi-eventbus</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Enable EventBus

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

## Configuration

### Thread Pool Configuration

All event types share the same thread pool configuration:

```yaml
shinyi:
  eventbus:
    thread-pool-core-size: 4        # Core thread pool size
    thread-pool-max-size: 8         # Maximum thread pool size
    thread-name-prefix: "eventbus-" # Thread name prefix
    max-queue-size: 10000           # Maximum queue size
    await-termination-seconds: 60   # Await termination seconds
```

---

## Guava EventBus

### Description
Guava EventBus is a local, in-memory event bus suitable for single-instance applications or intra-application event communication.

### Configuration
No additional configuration required. Always enabled.

### Usage Example

**Publishing:**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishUserCreatedEvent(String userId) {
    eventListenerRegistryManager.publish("guava", 
        EventModel.build("user.created", userId));
}
```

**Listening:**
```java
@EventBusListener(name = "guava", topic = "user.created")
public void onUserCreated(EventModel<String> event) {
    System.out.println("User created: " + event.getEntity());
}
```

---

## Spring ApplicationEvent

### Description
Uses Spring's built-in ApplicationEvent system for event publishing and listening within the Spring context.

### Configuration
No additional configuration required. Always enabled.

### Usage Example

**Publishing:**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishSpringEvent(Object data) {
    eventListenerRegistryManager.publish("spring", 
        EventModel.build("spring.event", data));
}
```

**Listening:**
```java
@EventBusListener(name = "spring", topic = "spring.event")
public void onSpringEvent(EventModel<Object> event) {
    System.out.println("Spring event received: " + event.getEntity());
}
```

---

## RabbitMQ

### Description
Enterprise-grade message broker supporting complex routing, multiple exchange types, and reliable message delivery.

### Configuration Parameters

```yaml
shinyi:
  eventbus:
    rabbit-mq:
      connect-configs:
        default-rabbit:
          # Basic Settings
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
          virtual-host: /
          
          # Exchange Settings
          exchange: my-exchange
          exchange-type: topic    # direct, fanout, topic, headers
          
          # Queue Settings
          queue: my-queue
          routing-key: my.routing.key
          
          # Producer Settings
          publisher-confirms: true
          publisher-retry: true
          send-msg-timeout: 3000
          
          # Consumer Settings
          concurrent-consumers: 3
          max-concurrent-consumers: 10
          prefetch-count: 250
          consumer-timeout-millis: 10000
          
          # Message Settings
          durable: true
          auto-delete: false
          skip-create-producer: false
```

### Configuration Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| host | String | localhost | RabbitMQ server host |
| port | int | 5672 | RabbitMQ server port |
| username | String | guest | Username |
| password | String | guest | Password |
| virtual-host | String | / | Virtual host |
| exchange | String | - | Exchange name |
| exchange-type | String | direct | Exchange type (direct/fanout/topic/headers) |
| queue | String | - | Queue name |
| routing-key | String | - | Routing key |
| durable | boolean | true | Queue durability |
| auto-delete | boolean | false | Auto delete queue |
| concurrent-consumers | int | 1 | Number of concurrent consumers |
| prefetch-count | int | 250 | Prefetch count |

### Usage Example

**Publishing:**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishOrderEvent(OrderDTO order) {
    // Synchronous publish
    eventListenerRegistryManager.publish("rabbitmq", 
        EventModel.build("order.created", order));
    
    // Asynchronous publish
    eventListenerRegistryManager.publish("rabbitmq", 
        EventModel.build("order.created", order, true));
}
```

**Listening:**
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
    System.out.println("Order created: " + order.getId());
}
```

**Advanced - Multiple Exchanges:**
```yaml
shinyi:
  eventbus:
    rabbit-mq:
      connect-configs:
        order-rabbit:
          is-default: false
          host: localhost
          exchange: order-exchange
          exchange-type: topic
        payment-rabbit:
          is-default: false
          host: localhost
          exchange: payment-exchange
          exchange-type: direct
```

---

## RocketMQ

### Description
Distributed messaging system from Alibaba, supporting ordered messages, transactional messages, and delayed messages.

### Configuration Parameters

```yaml
shinyi:
  eventbus:
    rocket-mq:
      connect-configs:
        default-rocket:
          # Basic Settings
          is-default: true
          namesrv-addr: localhost:9876
          group-name: producer-group
          
          # Producer Settings
          send-message-timeout: 3000
          compress-message-body-threshold: 4096
          max-message-size: 4194304
          
          # Consumer Settings
          consume-thread-min: 20
          consume-thread-max: 64
          consume-message-batch-max-size: 1
```

### Configuration Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| namesrv-addr | String | - | NameServer address |
| group-name | String | - | Producer/Consumer group name |
| send-message-timeout | int | 3000 | Send timeout (ms) |
| compress-message-body-threshold | int | 4096 | Message compression threshold |
| max-message-size | int | 4194304 | Maximum message size |
| consume-thread-min | int | 10 | Minimum consumer threads |
| consume-thread-max | int | 64 | Maximum consumer threads |

### Usage Example

**Publishing:**
```java
@EventBusListener(name = "rocketmq", topic = "user.registered")
public void onUserRegistered(EventModel<UserDTO> event) {
    System.out.println("User registered: " + event.getEntity());
}
```

**Listening:**
```java
@EventBusListener(
    name = "rocketmq",
    topic = "user.registered",
    group = "user-service",
    tags = "register,notification"
)
public void onUserRegistered(EventModel<UserDTO> event) {
    UserDTO user = event.getEntity();
    // Process user registration
}
```

---

## Kafka

### Description
Distributed event streaming platform suitable for high-throughput, real-time data pipelines and event-driven microservices.

### Configuration Parameters

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        default-kafka:
          # Basic Settings
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
          
          # Producer Settings
          acks: 1                    # 0, 1, all
          retries: 3
          batch-size: 16384
          linger-ms: 1
          buffer-memory: 33554432
          max-in-flight-requests-per-connection: 5
          
          # Consumer Settings
          auto-offset-reset: earliest  # earliest, latest, none
          enable-auto-commit: true
          auto-commit-interval-ms: 5000
          session-timeout-ms: 30000
          max-poll-records: 500
          max-poll-interval-ms: 300000
          
          # Advanced Settings
          client-id: my-client-id
          receive-buffer-bytes: 65536
          send-buffer-bytes: 131072
          
          # Serialization (auto-configured)
          key-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
          value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
```

### Configuration Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| bootstrap-servers | String | - | Kafka brokers (comma-separated) |
| topic | String | - | Default topic |
| group-id | String | - | Consumer group ID |
| acks | String | 1 | Acknowledgment level |
| retries | int | 3 | Number of retries |
| batch-size | int | 16384 | Batch size (bytes) |
| linger-ms | int | 1 | Batch linger time (ms) |
| buffer-memory | int | 33554432 | Buffer memory (bytes) |
| auto-offset-reset | String | earliest | Offset reset policy |
| enable-auto-commit | boolean | true | Auto commit enabled |
| session-timeout-ms | int | 30000 | Session timeout (ms) |
| max-poll-records | int | 500 | Max records per poll |

### Usage Example

**Publishing:**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishUserLoginEvent(LoginEvent event) {
    // Synchronous publish
    eventListenerRegistryManager.publish("kafka", 
        EventModel.build("user.login", event, false));
    
    // Asynchronous publish
    eventListenerRegistryManager.publish("kafka", 
        EventModel.build("user.login", event, true));
}
```

**Listening:**
```java
@EventBusListener(
    name = "kafka",
    topic = "user.login",
    group = "login-service"
)
public void onUserLogin(EventModel<LoginEvent> event) {
    LoginEvent login = event.getEntity();
    System.out.println("User login: " + login.getUserId());
}
```

**Serialization Options:**
```java
// Default JSON serialization
@EventBusListener(name = "kafka", topic = "event.json", serializeType = "DEFAULT")
public void onJsonEvent(EventModel<MyEvent> event) { }

// Raw message (no deserialization)
@EventBusListener(name = "kafka", topic = "event.raw", serializeType = "MSG")
public void onRawEvent(EventModel<?> event) { 
    byte[] rawData = event.getRawData();
}
```

---

## Redis

### Description
Redis-based event bus supporting both Pub/Sub for real-time messaging and Stream for persistent messaging with consumer groups.

### Configuration Parameters

```yaml
shinyi:
  eventbus:
    redis:
      connect-configs:
        default-redis:
          # Basic Settings
          is-default: true
          host: localhost
          port: 6379
          password:           # Optional
          database: 0
          
          # Connection Settings
          connection-timeout: 2000
          socket-timeout: 2000
          
          # Pub/Sub Settings
          channel-prefix: "eventbus:"
          
          # Stream Settings
          use-stream: false          # Use Stream instead of Pub/Sub
          stream-key: my-stream      # Stream key (if use-stream=true)
          group: my-group            # Consumer group (if use-stream=true)
```

### Configuration Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| host | String | localhost | Redis server host |
| port | int | 6379 | Redis server port |
| password | String | - | Password (optional) |
| database | int | 0 | Database index |
| connection-timeout | int | 2000 | Connection timeout (ms) |
| socket-timeout | int | 2000 | Socket timeout (ms) |
| channel-prefix | String | eventbus: | Channel prefix for Pub/Sub |
| use-stream | boolean | false | Use Redis Stream instead of Pub/Sub |
| stream-key | String | - | Stream key |
| group | String | - | Consumer group name |

### Usage Example

**Publishing (Pub/Sub):**
```java
@Autowired
private EventListenerRegistryManager eventListenerRegistryManager;

public void publishRedisEvent(DataEvent event) {
    eventListenerRegistryManager.publish("redis", 
        EventModel.build("user.action", event));
}
```

**Listening (Pub/Sub):**
```java
@EventBusListener(
    name = "redis",
    topic = "user.action",
    group = "action-processor"
)
public void onUserAction(EventModel<DataEvent> event) {
    DataEvent data = event.getEntity();
    System.out.println("User action: " + data.getType());
}
```

**Using Redis Stream:**
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
    // Process stream event
}
```

---

## Event Model

### Creating Events

```java
// Simple string event
EventModel<String> event1 = EventModel.build("topic.name", "message");

// Object event
EventModel<OrderDTO> event2 = EventModel.build("order.created", orderDTO);

// With async flag
EventModel<EventData> event3 = EventModel.build("topic.name", data, true); // async
```

### Event Fields

| Field | Type | Description |
|-------|------|-------------|
| eventId | String | Unique event ID |
| topic | String | Event topic |
| entity | Object | Event payload |
| serializeType | String | Serialization type |
| group | String | Consumer group |
| isAsync | boolean | Async publish flag |
| rawData | byte[] | Raw message data |
| driveType | String | Event bus type |

---

## Serialization Types

| Type | Description |
|------|-------------|
| DEFAULT | Standard JSON serialization |
| JSON | JSON to object mapping |
| BASIC | String or byte array |
| MSG | Raw message (MQ specific) |

```java
@EventBusListener(name = "kafka", topic = "log", serializeType = "BASIC")
public void onLog(EventModel<String> event) {
    String log = event.getEntity();
}
```

---

## Best Practices

1. **Event Design**: Use meaningful topic names (e.g., `order.created`, `user.login`)
2. **Error Handling**: Implement proper error handling in listeners
3. **Idempotency**: Design events to be idempotent when possible
4. **Monitoring**: Add logging and monitoring for event processing
5. **Thread Safety**: Be aware of thread safety when sharing state between listeners

---

## Advanced Topics

### Multiple Event Bus Instances

You can configure multiple instances of the same MQ type with different names:

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

### Context Propagation

Events automatically propagate MDC (Mapped Diagnostic Context) and ThreadLocal values:

```java
MDC.put("traceId", "abc123");
eventListenerRegistryManager.publish("kafka", EventModel.build("event", data));
```

### Event Callback

For async publishing, you can receive callback on success or failure:

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onSuccess(EventResult result) {
        System.out.println("Message sent: " + result.getMessageId());
    }
    
    @Override
    public void onFailure(EventResult result, Throwable throwable) {
        System.out.println("Message failed: " + throwable.getMessage());
    }
};

EventModel<EventData> event = EventModel.build("topic", data, true);
event.setEventCallback(callback);
eventListenerRegistryManager.publish("kafka", event);
```
