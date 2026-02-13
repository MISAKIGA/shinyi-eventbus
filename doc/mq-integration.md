# MQ Integration Guide

This guide explains how to extend Shinyi EventBus with new Message Queue (MQ) providers.

## Extending the Library

Shinyi EventBus uses a registry-based system, making it easy to add support for other messaging systems like Kafka, ActiveMQ, or Redis Streams.

### Step 1: Implement `EventListenerRegistry`

Create a new class implementing `EventListenerRegistry<EventModel<?>>`.

```java
public class KafkaEventListenerRegistry implements EventListenerRegistry<EventModel<?>> {

    private final String name;
    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;

    public KafkaEventListenerRegistry(String name, KafkaProperties properties) {
        this.name = name;
        this.consumer = new KafkaConsumer<>(properties.consumerConfigs());
        this.producer = new KafkaProducer<>(properties.producerConfigs());
    }

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.KAFKA;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<EventModel<?>>> listeners) {
        // Subscribe to topics based on listeners
        List<String> topics = listeners.stream()
            .map(EventListener::getTopic)
            .collect(Collectors.toList());
        consumer.subscribe(topics);
        
        // Start consumption loop in a separate thread
        new Thread(() -> {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    // Find matching listeners and invoke them
                    EventModel event = deserialize(record.value());
                    listeners.stream()
                        .filter(l -> l.getTopic().equals(record.topic()))
                        .forEach(l -> l.onEvent(event));
                }
            }
        }).start();
    }

    @Override
    public void publish(EventModel<?> event) {
        producer.send(new ProducerRecord<>(event.getTopic(), serialize(event)));
    }

    @Override
    public void close() {
        consumer.close();
        producer.close();
    }
}
```

### Step 2: Create Configuration Properties

Define configuration properties for your MQ.

```java
@ConfigurationProperties(prefix = "shinyi.eventbus.kafka")
@Data
public class KafkaConfig {
    private Map<String, KafkaConnectConfig> connectConfigs;
}
```

### Step 3: Create Auto-Configuration

Create a Spring configuration class to register your registry bean.

```java
@Configuration
@EnableConfigurationProperties(KafkaConfig.class)
public class KafkaAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shinyi.eventbus.kafka", name = "enabled", havingValue = "true")
    public KafkaEventListenerRegistry kafkaEventListenerRegistry(KafkaConfig config) {
        return new KafkaEventListenerRegistry("kafka", config.getConnectConfigs().get("default"));
    }
}
```

### Step 4: Add to `spring.factories` (or imports)

Ensure your auto-configuration is picked up by Spring Boot. Add it to `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Using the New Integration

Once implemented, you can use the new MQ type in your application.

```yaml
shinyi:
  eventbus:
    kafka:
      enabled: true
      connect-configs:
        default:
          bootstrap-servers: localhost:9092
```

And listen to events:

```java
@EventBusListener(name = "kafka", topic = "my-topic")
public void onEvent(EventModel event) {
    // Handle Kafka event
}
```
