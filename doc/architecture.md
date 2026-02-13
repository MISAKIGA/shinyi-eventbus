# Architecture Design

## Overview

Shinyi EventBus is designed to decouple event producers and consumers within a Spring Boot application, supporting both local in-memory event dispatching and remote message queue integration. The core architecture revolves around a unified event model and a registry-based listener management system.

## Core Concepts

### 1. Unified Event Model (`EventModel`)
All events, regardless of their source (Guava, RabbitMQ, RocketMQ), are encapsulated in a generic `EventModel<T>`. This model carries:
- **Event ID**: Unique identifier for tracing.
- **Topic**: The subject of the event.
- **Entity**: The payload (generic type `T`).
- **Metadata**: Group, Tags, DriveType (source).
- **Control Flags**: `enableAsync`, `serializeType`.

### 2. Event Listener (`EventListener`)
The `EventListener` interface defines how an event should be processed.
- **MethodEventListener**: The primary implementation that wraps a method annotated with `@EventBusListener`. It handles reflection invocation and argument binding.
- **ExecutableEventListener**: An extension for executable listeners.

### 3. Registry Pattern (`EventListenerRegistry`)
To support multiple event backends, the system uses the Registry pattern.
- `EventListenerRegistry<T>`: Interface for registering listeners and publishing events to a specific backend.
- **Implementations**:
  - `GuavaEventListenerRegistry`: Uses Guava's `EventBus` for local, in-memory events.
  - `SpringEventListenerRegistry`: Uses Spring's `ApplicationEventPublisher`.
  - `RabbitMqEventListenerRegistry`: Wraps RabbitMQ client for AMQP messaging.
  - `RocketMqEventListenerRegistry`: Wraps RocketMQ client.
  
### 4. Registry Manager (`EventListenerRegistryManager`)
The central orchestrator.
- Scans for beans with `@EventBusListener` methods.
- Delegates registration to the appropriate `EventListenerRegistry` based on the `name` attribute of the annotation.
- Provides a unified `publish(String type, EventModel event)` method to dispatch events to the correct backend.

## Flow Diagram

```mermaid
graph TD
    Publisher[Publisher Code] -->|publish("rabbitmq", event)| Manager[EventListenerRegistryManager]
    Manager -->|Route based on type| RabbitRegistry[RabbitMqEventListenerRegistry]
    Manager -->|Route based on type| GuavaRegistry[GuavaEventListenerRegistry]
    
    RabbitRegistry -->|Serialize & Send| RabbitMQ[(RabbitMQ Broker)]
    GuavaRegistry -->|Post to Bus| GuavaBus[Guava EventBus]
    
    RabbitMQ -->|Consume| RabbitListener[RabbitMQ Consumer]
    RabbitListener -->|Deserialize & Invoke| MethodListener[MethodEventListener]
    
    GuavaBus -->|Dispatch| MethodListener
```

## Async Execution

A global thread pool (`ThreadPoolTaskExecutor`) is configured via `EventBusProperties` to handle asynchronous event processing where applicable (e.g., local async listeners). Remote MQs typically use their own client threads but may offload processing to this pool if configured.

## Context Propagation

`EventBusContext` uses `TransmittableThreadLocal` (TTL) or `InheritableThreadLocal` to propagate context (like user info, trace IDs) across asynchronous boundaries, ensuring that context is preserved during event processing.
