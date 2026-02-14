package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
public class KafkaMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final KafkaConnectConfig kafkaConnectConfig;

    private KafkaProducer<String, byte[]> producer;
    private final Set<KafkaConsumer<String, byte[]>> consumerSet = new ConcurrentHashSet<>();
    private final Set<ExecutorService> executorSet = new ConcurrentHashSet<>();
    protected final Serializer serializer = new BaseSerializer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.KAFKA;
    }

    public void init() {
        if (kafkaConnectConfig.getBootstrapServers() == null || kafkaConnectConfig.getBootstrapServers().isEmpty()) {
            throw new IllegalArgumentException("Kafka bootstrapServers cannot be empty");
        }
        Properties producerProps = kafkaConnectConfig.toProducerProperties();
        producer = new KafkaProducer<>(producerProps);
        log.info("Kafka Producer initialized for {}", kafkaConnectConfig.getBootstrapServers());
    }

    @Override
    public void initRegistryEventListener(List<com.shinyi.eventbus.EventListener<T>> listener) {
        if (listener == null) {
            return;
        }
        CompletableFuture[] futures = listener.stream()
                .filter(l -> CollectionUtil.isNotEmpty(l.registryBeanName()) && CollectionUtil.contains(l.registryBeanName(), registryBeanName)
                        || CollectionUtil.isEmpty(l.registryBeanName()) && kafkaConnectConfig.getIsDefault())
                .map(l -> CompletableFuture.runAsync(() -> initConsumer(l)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    private void initConsumer(com.shinyi.eventbus.EventListener<T> listener) {
        Properties consumerProps = kafkaConnectConfig.toConsumerProperties();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, listener.group());
        
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumerSet.add(consumer);

        String topic = listener.topic();
        if (topic == null || topic.isEmpty()) {
            topic = kafkaConnectConfig.getTopic();
        }
        final String finalTopic = topic;
        consumer.subscribe(Collections.singletonList(finalTopic));

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "kafka-consumer-" + finalTopic));
        executorSet.add(executor);

        final com.shinyi.eventbus.EventListener<T> finalListener = listener;
        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                        for (ConsumerRecord<String, byte[]> record : records) {
                            try {
                                if (record.value() == null || record.value().length == 0) {
                                    log.warn("Message body is empty, skipping. offset={}", record.offset());
                                    continue;
                                }
                                EventModel<?> eventModel = deserialize(record.value(), record.offset() + "", finalListener);
                                finalListener.onMessage((T) eventModel);
                            } catch (Exception e) {
                                log.warn("Message processing failed: " + e.getMessage(), e);
                            }
                        }
                    } catch (WakeupException e) {
                        break;
                    }
                }
            } finally {
                consumer.close();
            }
        });

        log.info("Kafka consumer started for topic: {}, group: {}", finalTopic, finalListener.group());
    }

    protected EventModel<?> deserialize(byte[] body, String consumerTag, com.shinyi.eventbus.EventListener<T> listener) {
        EventModel<?> eventModel;
        try {
            eventModel = serializer.deserialize(body, listener.serializeType(), listener.entityType());
            if ("MSG".equals(listener.serializeType())) {
                eventModel = EventModel.build(listener.topic(), null);
            }
        } catch (Throwable e) {
            log.warn(registryBeanName + " msgId: " + consumerTag + " Message deserialization failed: " + new String(body, StandardCharsets.UTF_8));
            eventModel = EventModel.build(listener.topic(), null);
        }
        eventModel.setRawData(body);
        eventModel.setGroup(listener.group());
        eventModel.setDriveType(registryBeanName + "#" + getEventBusType().getTypeName());
        if (eventModel.getEventId() == null) {
            eventModel.setEventId(consumerTag);
        }
        if (eventModel.getTopic() == null) {
            eventModel.setTopic(listener.topic());
        }
        return eventModel;
    }

    @Override
    public void publish(T eventModel) {
        final EventCallback eventCallback = eventModel.getEventCallback();
        final EventResult eventResult = new EventResult();
        try {
            byte[] body = serializer.serialize(eventModel, eventModel.getSerializeType());
            String topic = eventModel.getTopic();
            if (topic == null || topic.isEmpty()) {
                topic = kafkaConnectConfig.getTopic();
            }
            final String finalTopic = topic;
            String key = eventModel.getEventId();
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(finalTopic, key, body);
            
            if (eventModel.isEnableAsync()) {
                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        eventResult.setMessageId(String.valueOf(metadata.offset()));
                        eventResult.setTopic(finalTopic);
                        if (eventCallback != null) {
                            eventCallback.onSuccess(eventResult);
                        }
                    } else {
                        if (eventCallback != null) {
                            eventCallback.onFailure(eventResult, exception);
                        } else {
                            throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_ERROR, exception.getMessage());
                        }
                    }
                });
            } else {
                RecordMetadata metadata = producer.send(record).get();
                eventResult.setMessageId(String.valueOf(metadata.offset()));
                eventResult.setTopic(topic);
                if (eventCallback != null) {
                    eventCallback.onSuccess(eventResult);
                }
            }
        } catch (Exception e) {
            log.warn("{} Publish message exception: {}", getEventBusType().getTypeName(), e.getMessage());
            if (eventCallback != null) {
                eventCallback.onFailure(eventResult, e);
            } else {
                throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_ERROR, e.getMessage());
            }
        }
    }

    @Override
    public void close() throws Exception {
        for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
            try {
                consumer.wakeup();
            } catch (Throwable ignored) {
            }
        }
        for (ExecutorService executor : executorSet) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        if (producer != null) {
            try {
                producer.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
