# Implementation Details

## Project Structure

The project is structured as a Maven project with clear separation of concerns:

- `com.shinyi.eventbus`
  - `anno`: Annotations (`@EventBusListener`, `@EnableEventBus`)
  - `config`: Spring configuration and property classes.
  - `exception`: Custom exceptions (`EventBusException`).
  - `listener`: Base listener implementations (`MethodEventListener`).
  - `registry`: Implementations for different event bus types (`Guava`, `Spring`, `RabbitMQ`, `RocketMQ`).
  - `serialize`: Serialization utilities (`Serializer`, `SerializeType`).
  - `support`: Core logic (`EventListenerRegistryManager`).
  - `util`: Helpers (`JsonUtils`).

## Key Classes

### 1. `EventListenerRegistryManager` (`support/EventListenerRegistryManager.java`)
This is the heart of the library. It implements `SmartLifecycle` to hook into the Spring application lifecycle.
- **Scanning**: Uses `MethodIntrospector` and `AnnotatedElementUtils` to find methods annotated with `@EventBusListener`.
- **Filtering**: Skips `@Lazy` beans to avoid unnecessary initialization.
- **Registration**: Creates `MethodEventListener` instances and registers them with the corresponding `EventListenerRegistry`.
- **Publishing**: Dispatches events to the correct registry based on the type string.

### 2. `MethodEventListener` (`listener/MethodEventListener.java`)
Wraps a reflective `Method` call. It handles:
- Checking if the event matches the expected type.
- Deserializing the payload if needed (`deserializeType`).
- Invoking the target method on the target bean.
- Argument binding.

### 3. `EventListenerRegistry` Implementations (`registry/*.java`)
Each implementation adapts a specific messaging system:
- **`GuavaEventListenerRegistry`**: Wraps Guava's `EventBus`. Registers listeners directly. Published events are posted to the bus.
- **`RabbitMqEventListenerRegistry`**: Uses RabbitMQ Java client. Creates queues, exchanges, and bindings based on listener annotations. Published events are sent to the exchange.
- **`RocketMqEventListenerRegistry`**: Uses RocketMQ/Aliyun ONS client. Subscribes to topics and tags. published events are sent via producer.

### 4. Configuration (`config/*.java`)
Configuration is loaded via `@ConfigurationProperties(prefix = "shinyi.eventbus")`.
- **`EventBusProperties`**: Global settings like thread pool size.
- **`RabbitMqConfig`**: Map of `RabbitMqConnectConfig` keyed by connection name.
- **`RocketMqConfig`**: Map of `RocketMqConnectConfig` keyed by connection name.

### 5. Serialization (`serialize/*.java`)
The library supports flexible serialization strategies defined in `SerializeType`:
- **DEFAULT**: JSON serialization of the entire `EventModel`.
- **BASIC**: Simple types (String, byte[]).
- **JSON**: JSON serialization of the `entity` field only.
- **MSG**: Raw message object.

### 6. Error Handling
Global error handling logic can be customized, but currently uses `SimpleErrorHandler` or throws `EventBusException`. Async errors are logged, while sync errors propagate to the caller.
