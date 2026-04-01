package com.shinyi.eventbus.registry;

/**
 * @deprecated Use {@link OptimizedKafkaMqEventListenerRegistry} instead.
 * OptimizedKafkaMqEventListenerRegistry provides better performance through:
 * - Object pooling (EventResult reuse)
 * - Disabled hot-path logging
 * - Pre-computed default topic
 *
 * This class is kept for backward compatibility only.
 */

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import com.shinyi.eventbus.monitor.MetricsHolder;
import com.shinyi.eventbus.monitor.PerformanceMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
@Deprecated
public class KafkaMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final KafkaConnectConfig kafkaConnectConfig;

    private KafkaProducer<String, byte[]> producer;
    private final Set<KafkaConsumer<String, byte[]>> consumerSet = new ConcurrentHashSet<>();
    private final Set<ExecutorService> executorSet = new ConcurrentHashSet<>();
    protected final Serializer serializer = new BaseSerializer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    // EOS: Offset tracking for manual commit
    private final Map<KafkaConsumer<String, byte[]>, OffsetCommitState> offsetStates = new ConcurrentHashMap<>();

    /**
     * EOS: Offset tracking state per consumer
     */
    private static class OffsetCommitState {
        Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new ConcurrentHashMap<>();
        AtomicInteger processedCount = new AtomicInteger(0);
    }

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.KAFKA;
    }

    public void init() {
        if (kafkaConnectConfig.getBootstrapServers() == null || kafkaConnectConfig.getBootstrapServers().isEmpty()) {
            throw new IllegalArgumentException("Kafka bootstrapServers cannot be empty");
        }

        // Configure Kerberos system properties before creating clients
        kafkaConnectConfig.configureKerberosSystemProperties();

        Properties producerProps = kafkaConnectConfig.toProducerProperties();
        producer = new KafkaProducer<>(producerProps);
        log.info("Kafka Producer initialized for {} with security protocol: {}",
                kafkaConnectConfig.getBootstrapServers(), kafkaConnectConfig.getSecurityProtocol());
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
        // EOS: Get EOS settings - check listener annotation first, fall back to global config
        final boolean eosEnabled = listener.exactlyOnce() || kafkaConnectConfig.isEnableManualCommit();
        final int commitBatchSize = listener.commitBatchSize() > 0
            ? listener.commitBatchSize()
            : kafkaConnectConfig.getCommitBatchSize();

        Properties consumerProps = kafkaConnectConfig.toConsumerProperties(eosEnabled);
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
                                // EOS: Track offset for manual commit after successful processing
                                if (eosEnabled) {
                                    trackOffsetAndCommit(consumer, record, commitBatchSize);
                                }
                                // 记录消费成功指标
                                MetricsHolder.increment(registryBeanName, finalTopic, "events.consumed", 1);
                            } catch (Exception e) {
                                // 记录消费失败指标
                                MetricsHolder.increment(registryBeanName, finalTopic, "events.failed", 1);
                                log.warn("Message processing failed: " + e.getMessage(), e);
                            }
                        }
                    } catch (WakeupException e) {
                        break;
                    }
                }
            } finally {
                // EOS: Commit pending offsets before shutdown and cleanup
                if (eosEnabled) {
                    commitPendingOffsets(consumer, true);  // true = remove after commit
                }
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

    /**
     * EOS: Track offset and commit when batch size reached
     */
    private void trackOffsetAndCommit(KafkaConsumer<String, byte[]> consumer,
                                     ConsumerRecord<String, byte[]> record,
                                     int batchSize) {
        OffsetCommitState state = offsetStates.computeIfAbsent(consumer, k -> new OffsetCommitState());

        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        state.pendingOffsets.put(tp, new OffsetAndMetadata(record.offset() + 1));
        state.processedCount.incrementAndGet();

        // Use processedCount (number of messages) not pendingOffsets.size() (number of partitions)
        if (state.processedCount.get() >= batchSize) {
            commitPendingOffsets(consumer);
        }
    }

    /**
     * EOS: Commit pending offsets to Kafka
     */
    private void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer) {
        commitPendingOffsets(consumer, false);
    }

    /**
     * EOS: Commit pending offsets to Kafka
     * @param removeAfterCommit if true, remove the consumer entry from offsetStates after committing
     */
    private void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer, boolean removeAfterCommit) {
        OffsetCommitState state = offsetStates.get(consumer);
        if (state != null && !state.pendingOffsets.isEmpty()) {
            try {
                consumer.commitSync(new HashMap<>(state.pendingOffsets));
                log.debug("EOS: Committed offsets for {} partitions", state.pendingOffsets.size());
                state.pendingOffsets.clear();
                state.processedCount.set(0);
            } catch (Exception e) {
                log.error("EOS: Failed to commit offsets: " + e.getMessage(), e);
            }
        }
        if (removeAfterCommit) {
            offsetStates.remove(consumer);
        }
    }

    @Override
    public void publish(T eventModel) {
        final EventCallback eventCallback = eventModel.getEventCallback();
        final EventResult eventResult = new EventResult();

        // 耗时统计 - 对象创建
        long createStart = System.nanoTime();

        try {
            // 耗时统计 - 序列化
            long serializeStart = System.nanoTime();
            byte[] body = serializer.serialize(eventModel, eventModel.getSerializeType());
            PerformanceMonitor.record("kafka.serialize", System.nanoTime() - serializeStart);

            String topic = eventModel.getTopic();
            if (topic == null || topic.isEmpty()) {
                topic = kafkaConnectConfig.getTopic();
            }
            final String finalTopic = topic;
            String key = eventModel.getEventId();

            // 耗时统计 - ProducerRecord创建
            long recordCreateStart = System.nanoTime();
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(finalTopic, key, body);
            PerformanceMonitor.record("kafka.recordCreate", System.nanoTime() - recordCreateStart);

            // 耗时统计 - 实际发送
            long sendStart = System.nanoTime();
            if (eventModel.isEnableAsync()) {
                producer.send(record, (metadata, exception) -> {
                    PerformanceMonitor.record("kafka.send", System.nanoTime() - sendStart);
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
                PerformanceMonitor.record("kafka.send", System.nanoTime() - sendStart);
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
        } finally {
            PerformanceMonitor.record("kafka.objectAllocation", System.nanoTime() - createStart);
        }
    }

    @Override
    public void publishBatch(List<T> events, BatchEventCallback callback) {
        if (events == null || events.isEmpty()) {
            return;
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }

        List<EventResult> results = new ArrayList<>(events.size());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(events.size());

        for (T event : events) {
            EventResult result = new EventResult();
            results.add(result);

            try {
                byte[] body = serializer.serialize(event, event.getSerializeType());
                String topic = event.getTopic();
                if (topic == null || topic.isEmpty()) {
                    topic = kafkaConnectConfig.getTopic();
                }
                final String finalTopic = topic;
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(finalTopic, event.getEventId(), body);

                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        result.setMessageId(String.valueOf(metadata.offset()));
                        result.setTopic(finalTopic);
                    } else {
                        errors.add(exception);
                    }
                    latch.countDown();
                });
            } catch (Exception e) {
                errors.add(e);
                latch.countDown();
            }
        }

        // 异步等待所有发送完成并回调
        CompletableFuture.runAsync(() -> {
            try {
                // 等待所有send回调完成
                boolean completed = latch.await(5, TimeUnit.MINUTES);
                // 刷新缓冲区确保所有消息被发送
                producer.flush();

                if (!completed) {
                    callback.onBatchFailure(results, new RuntimeException("Batch send timeout"));
                } else if (!errors.isEmpty()) {
                    callback.onBatchFailure(results, errors.get(0));
                } else {
                    callback.onBatchComplete(results);
                }
            } catch (Exception e) {
                callback.onBatchFailure(results, e);
            }
        });
    }

    @Override
    public void close() throws Exception {
        // EOS: Commit pending offsets for all consumers before shutdown and cleanup
        for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
            commitPendingOffsets(consumer, true);  // true = remove after commit
        }

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

    /**
     * Flush the producer buffer to ensure all pending messages are sent.
     * 对齐 kafka-demo：每 N 条消息 flush 一次，让 broker 分批处理
     */
    @Override
    public void flush() {
        if (producer != null) {
            producer.flush();
        }
    }
}
