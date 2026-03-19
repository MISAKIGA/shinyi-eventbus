# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Shinyi EventBus is a lightweight, annotation-driven event bus framework for Spring Boot applications. It provides unified APIs for handling local events (Guava EventBus, Spring ApplicationContext) and distributed events (RabbitMQ, RocketMQ, Kafka, Redis).

## Build & Test Commands

**Note:** Tests are skipped by default in the Maven build.

```bash
# Compile (skips tests)
mvn compile

# Build entire project with tests
mvn clean install -DskipTests=false -Dmaven.compiler.skip=false

# Build without tests
mvn clean package -DskipTests

# Run a single test class
mvn test -DskipTests=false -Dmaven.compiler.skip=false -Dtest=EventBusTest

# Run a single test method
mvn test -DskipTests=false -Dmaven.compiler.skip=false -Dtest=EventBusTest#testMethodName

# Release build (sources, javadoc, GPG signing)
mvn clean deploy -P release
```

## Architecture

### Core Components

1. **EventModel<T>** - Unified event model encapsulating all events regardless of source. Contains event ID, topic, payload (generic T), metadata, and control flags (async, serializeType).

2. **@EventBusListener** - Annotation applied to methods to subscribe to events. Specifies `name` (bus type like "guava", "rabbitmq", "kafka"), `topic`, and MQ-specific settings.

3. **EventListenerRegistry** - Interface for each message queue implementation. Registry pattern allows swapping between local (Guava, Spring) and remote (RabbitMQ, RocketMQ, Kafka, Redis) buses.

4. **EventListenerRegistryManager** - Central orchestrator that scans for `@EventBusListener` methods and delegates to the appropriate registry based on the `name` attribute.

### Registry Implementations

| Registry | Type | Purpose |
|----------|------|---------|
| `GuavaEventListenerRegistry` | Local | In-memory events via Guava EventBus |
| `SpringEventListenerRegistry` | Local | Spring ApplicationEventPublisher |
| `RabbitMqEventListenerRegistry` | Remote | RabbitMQ AMQP messaging |
| `RocketMqEventListenerRegistry` | Remote | RocketMQ distributed messaging |
| `KafkaMqEventListenerRegistry` | Remote | Kafka event streaming |
| `RedisMqEventListenerRegistry` | Remote | Redis Pub/Sub and Stream |

### Package Structure

```
src/main/java/com/shinyi/eventbus/
├── anno/           # Annotations (@EventBusListener, @EnableEventBus)
├── config/         # Auto-configuration per MQ (kafka/, rabbit/, redis/, rocketmq/)
├── exception/      # Exception types with ErrorCode enum
├── listener/       # Event listener implementations (MethodEventListener, ExecutableEventListener)
├── registry/       # EventListenerRegistry implementations per bus type
├── serialize/      # Serializer interface for event serialization
├── support/        # EventListenerRegistryManager and error handlers
└── util/          # JsonUtils
```

## Key Technical Notes

- **Java 8+ target** - Configured in pom.xml
- **ThreadLocal context propagation** - Uses TransmittableThreadLocal (TTL) for context propagation across async boundaries
- **Annotation processor ordering** - Lombok must be processed before MapStruct; configured via `lombok-mapstruct-binding`
- **Optional MQ dependencies** - RabbitMQ, RocketMQ, Kafka, Redis clients are `provided` scope; add explicit dependencies for the MQ you use
- **Serialization** - Multiple serialization options (JSON, Byte Array, String, Native Object) via `SerializeType` enum

## Configuration

Configure via `application.yml` under `shinyi.eventbus.*`:

```yaml
shinyi:
  eventbus:
    thread-pool-core-size: 4
    thread-pool-max-size: 8
    max-queue-size: 1000
    rabbit-mq:
      connect-configs:
        default-rabbit:
          is-default: true
          host: localhost
          port: 5672
          username: guest
          password: guest
    kafka:
      connect-configs:
        default-kafka:
          is-default: true
          bootstrap-servers: localhost:9092
          topic: my-topic
          group-id: my-consumer-group
```

## Error Handling

- Custom exceptions extend `BaseException`
- All errors include `ErrorCode` enum values from `com.shinyi.eventbus.exception`
- Never swallow exceptions without logging or rethrowing
