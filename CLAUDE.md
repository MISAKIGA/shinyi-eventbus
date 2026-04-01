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

## 工作方式
- 在实现前先说明方法。
- 若需求有歧义、风险较高或影响较大，先澄清并等待批准，再开始写代码。
- Plan 只写方案，不写代码。
- 坚持 Spec Coding，不做 Vibe Coding。
- 优先迭代，使用 `/loop`。
- 完成后执行 `/simplify`。
- 你是统筹者，先指定一个 Claude 产出 plan。
- 基于 plan 将任务拆分后分配给不同的 Claude 并行或串行执行。
- 所有子任务应保持边界清晰、职责明确、便于独立验证。
- 完成后再指定一个 Claude 汇总结果并输出最终报告给我。

## 编码规则
- 代码中只允许使用英文。
- Spec 不依赖行号定位代码。
- 注释中不要写开发过程式说明。
- 优先用概念性描述定位代码，不用“文件路径 + 行号”。

## 拆分与范围控制
- 将任务拆分为低耦合、可独立验证的子任务，必要时使用 `/batch`。
- 重复出现 3 次的流程应沉淀为 Skill。
- 任务分配时优先控制单个 Claude 的上下文范围，避免把过多背景一次性注入到同一个上下文中。
- 只向负责该子任务的 Claude 提供完成任务所必需的最小上下文。
- 跨任务共享信息时，优先传递经过整理的结论、约束和接口，而不是完整过程性上下文。

## 质量要求
- 项目早期只保留最小必要质量标准：可运行、可验证、可回滚。
- 优先保证关键路径和高风险改动可验证。
- 处理 bug 时，先复现，再修复并验证。

## 纠错与协作
- 被纠正时，识别原因并改进做法；对重复性问题，沉淀为明确规则。
- 实现与审查分离：先完成方案或代码，再独立复核。
- 统筹者负责跟踪各 Claude 的输入、输出、依赖关系和验收结果，避免遗漏与重复劳动。
- 汇总报告应至少包含：任务目标、各子任务结果、验证结论、遗留风险、后续建议。

## 禁止事项
- 永远不要使用 `/init`。
- `CLAUDE.md` 应按项目实际需求编写，不要套用空泛模板。
- Avoid terms to describe development progress (`FIXED`, `Step`, `Week`, `Section`, `Phase`, `AC-x`, etc) in code comments or commit message or PR body.
- Avoid AI tools name (like Codex, Claude, Grok, Gemini, ...) in code comments or git commit message (including authorship) or PR body.
