# AGENTS.md - Shinyi EventBus Developer Guide

## Project Overview

Shinyi EventBus is a lightweight, annotation-driven event bus framework for Spring Boot applications. It provides unified APIs for handling local events (Guava, Spring ApplicationContext) and distributed events (RabbitMQ, RocketMQ, Kafka).

## Build & Test Commands

### Building the Project

```bash
# Compile the project (skips tests by default - see pom.xml)
mvn compile

# Build the entire project including tests
mvn clean install -DskipTests=false

# Build without running tests
mvn clean package -DskipTests

# Run the release profile (builds sources, javadoc, signs artifacts)
mvn clean deploy -P release
```

### Running Tests

**Note:** The pom.xml has `<skip>true</skip>` in the compiler plugin, meaning tests are skipped by default.

```bash
# Run all tests (requires overriding the skip setting)
mvn test -DskipTests=false -Dmaven.compiler.skip=false

# Run a single test class
mvn test -DskipTests=false -Dmaven.compiler.skip=false -Dtest=EventBusTest

# Run a single test method
mvn test -DskipTests=false -Dmaven.compiler.skip=false -Dtest=EventBusTest#testMethodName

# Run tests with verbose output
mvn test -DskipTests=false -Dmaven.compiler.skip=false -Dsurefire.useFile=false
```

### Code Quality

```bash
# Generate JavaDoc
mvn javadoc:javadoc

# Run with specific profile
mvn clean install -P release
```

## Code Style Guidelines

### Project Structure

```
src/
├── main/
│   ├── java/com/shinyi/eventbus/
│   │   ├── anno/          # Custom annotations (@EventBusListener, @EnableEventBus)
│   │   ├── config/        # Auto-configuration classes
│   │   ├── exception/     # Exception types (BaseException, EventBusException)
│   │   ├── listener/      # Event listener implementations
│   │   ├── registry/      # Event listener registries (Spring, Guava, RabbitMQ, etc.)
│   │   ├── serialize/     # Serialization interfaces
│   │   └── util/          # Utility classes
│   └── resources/
└── test/java/com/shinyi/eventbus/  # Test classes
```

### Naming Conventions

- **Classes:** PascalCase (e.g., `EventBusContext`, `MethodEventListener`)
- **Methods:** camelCase (e.g., `getContext()`, `publishEvent()`)
- **Constants:** UPPER_SNAKE_CASE (e.g., `DEFAULT_TOPIC`)
- **Packages:** lowercase with dots (e.g., `com.shinyi.eventbus.config`)
- **Variables:** camelCase, avoid single letters except loop indices

### Code Style

- **Indentation:** 4 spaces (no tabs)
- **Line Length:** Maximum 120 characters
- **Braces:** Same-line opening braces for methods/classes, newline for control statements
- **Imports:** Grouped order: java.*, javax.*, org.*, com.*, then static imports
- **Annotations:** Place on separate line before class/method declarations
- **Lombok:** Use Lombok annotations to reduce boilerplate (@Data, @Builder, @Slf4j, @Getter, @Setter)

### Type Guidelines

- Use concrete return types when possible; use interfaces for method parameters
- Avoid raw types; use generics properly (e.g., `List<EventModel<T>>` not `List`)
- Use `var` for local variables when type is obvious from right side
- Prefer `List` over `Collection` for return types unless mutability needed
- Use `Optional` for methods that may return null

### Error Handling

- Use custom exceptions extending `BaseException` (e.g., `EventBusException`)
- Always include `ErrorCode` enum values for errors
- Use `ErrorCode` enum in `com.shinyi.eventbus.exception` package
- Include meaningful error messages with parameter context
- Never swallow exceptions without logging or rethrowing

### Logging

- Use Lombok's `@Slf4j` for logger injection
- Log at appropriate levels: ERROR for failures, WARN for recoverable issues, INFO for important events
- Use parameterized logging: `log.info("Message: {}", value)` instead of concatenation
- Include context in log messages (trace IDs, relevant parameters)

### Testing

- Use JUnit 5 (Jupiter) for all tests
- Use Mockito for mocking dependencies
- Use `@ExtendWith(MockitoExtension.class)` for Mockito support
- Use `@InjectMocks` for the class under test, `@Mock` for dependencies
- Group related tests in the same test class
- Test naming: `methodName_shouldDoSomething_whenCondition()`

### Configuration

- Use Spring Boot configuration properties in `EventBusProperties`
- Configure via `application.yml` under `shinyi.eventbus.*`
- Use `@ConfigurationProperties` for type-safe configuration
- Provide sensible defaults in properties classes

### Spring Integration

- Use Spring's auto-configuration patterns (`@Configuration`, `@EnableAutoConfiguration`)
- Use `@ConditionalOnProperty` for optional features
- Implement `ApplicationContextAware` when access to Spring context needed
- Use `@Autowired` for dependency injection (or constructor injection preferred for new code)

### MQ Integration Patterns

- Support multiple message queues: RabbitMQ, RocketMQ, Kafka
- Use adapter pattern for different MQ clients
- Provide connection configuration classes per MQ type
- Use consistent serialization interface (`Serializer` interface)
- Support both sync and async event publishing

### Important Notes

- This project targets Java 8+ (configured in pom.xml)
- Uses MapStruct for mapping, ensure annotation processor ordering is correct
- TTL (TransmittableThreadLocal) is used for context propagation across threads
- Tests are skipped by default in the build; always use `-DskipTests=false`
