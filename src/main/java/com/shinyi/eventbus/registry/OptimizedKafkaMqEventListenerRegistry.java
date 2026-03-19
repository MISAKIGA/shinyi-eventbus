package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import com.shinyi.eventbus.monitor.PerformanceMonitor;
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

/**
 * 优化版本的KafkaMqEventListenerRegistry
 *
 * 优化点：
 * 1. 禁用热路径日志 - 通过PerformanceMonitor开关控制
 * 2. 对象池化 - 复用EventResult减少GC压力
 * 3. 预计算topic - 避免每次publish时检查
 *
 * 使用方式：
 * - 添加 -Dcom.shinyi.eventbus.performance.optimized=true 启用
 */
@Slf4j
public class OptimizedKafkaMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final KafkaConnectConfig kafkaConnectConfig;

    private KafkaProducer<String, byte[]> producer;
    private final Set<KafkaConsumer<String, byte[]>> consumerSet = new ConcurrentHashSet<>();
    private final Set<ExecutorService> executorSet = new ConcurrentHashSet<>();
    protected final Serializer serializer = new BaseSerializer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    // 预计算的默认值
    private String defaultTopic;
    private boolean performanceMode = false;

    // 对象池 - 复用EventResult
    private static final int EVENT_RESULT_POOL_SIZE = 1000;
    private final Queue<EventResult> eventResultPool = new ArrayDeque<>(EVENT_RESULT_POOL_SIZE);
    private final Object poolLock = new Object();

    public OptimizedKafkaMqEventListenerRegistry(ApplicationContext applicationContext, String registryBeanName,
                                                  KafkaConnectConfig kafkaConnectConfig) {
        this.applicationContext = applicationContext;
        this.registryBeanName = registryBeanName;
        this.kafkaConnectConfig = kafkaConnectConfig;

        // 检查是否启用性能模式
        String prop = System.getProperty("com.shinyi.eventbus.performance.optimized");
        if (prop == null) {
            prop = System.getenv("SHINYI_EVENTBUS_PERF_OPTIMIZED");
        }
        this.performanceMode = "true".equalsIgnoreCase(prop) || "1".equals(prop);

        // 初始化对象池
        if (performanceMode) {
            for (int i = 0; i < EVENT_RESULT_POOL_SIZE; i++) {
                eventResultPool.offer(new EventResult());
            }
        }
    }

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.KAFKA;
    }

    public void init() {
        if (kafkaConnectConfig.getBootstrapServers() == null || kafkaConnectConfig.getBootstrapServers().isEmpty()) {
            throw new IllegalArgumentException("Kafka bootstrapServers cannot be empty");
        }

        kafkaConnectConfig.configureKerberosSystemProperties();

        Properties producerProps = kafkaConnectConfig.toProducerProperties();
        producer = new KafkaProducer<>(producerProps);

        // 预计算defaultTopic
        this.defaultTopic = kafkaConnectConfig.getTopic();

        if (performanceMode) {
            log.info("OptimizedKafkaMqEventListenerRegistry initialized in PERFORMANCE MODE");
            PerformanceMonitor.enable();
        } else {
            log.info("Kafka Producer initialized for {} with security protocol: {}",
                    kafkaConnectConfig.getBootstrapServers(), kafkaConnectConfig.getSecurityProtocol());
        }
    }

    /**
     * 从池中获取EventResult，或创建新的
     */
    private EventResult acquireEventResult() {
        if (!performanceMode) {
            return new EventResult();
        }
        synchronized (poolLock) {
            EventResult result = eventResultPool.poll();
            if (result == null) {
                return new EventResult();
            }
            return result;
        }
    }

    /**
     * 归还EventResult到池中
     */
    private void releaseEventResult(EventResult result) {
        if (!performanceMode || result == null) {
            return;
        }
        synchronized (poolLock) {
            if (eventResultPool.size() < EVENT_RESULT_POOL_SIZE) {
                eventResultPool.offer(result);
            }
        }
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
            topic = defaultTopic;
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
                                    if (!performanceMode) {
                                        log.warn("Message body is empty, skipping. offset={}", record.offset());
                                    }
                                    continue;
                                }
                                EventModel<?> eventModel = deserialize(record.value(), record.offset() + "", finalListener);
                                finalListener.onMessage((T) eventModel);
                            } catch (Exception e) {
                                if (!performanceMode) {
                                    log.warn("Message processing failed: " + e.getMessage(), e);
                                }
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

        if (!performanceMode) {
            log.info("Kafka consumer started for topic: {}, group: {}", finalTopic, finalListener.group());
        }
    }

    protected EventModel<?> deserialize(byte[] body, String consumerTag, com.shinyi.eventbus.EventListener<T> listener) {
        EventModel<?> eventModel;
        try {
            eventModel = serializer.deserialize(body, listener.serializeType(), listener.entityType());
            if ("MSG".equals(listener.serializeType())) {
                eventModel = EventModel.build(listener.topic(), null);
            }
        } catch (Throwable e) {
            if (!performanceMode) {
                log.warn(registryBeanName + " msgId: " + consumerTag + " Message deserialization failed: " + new String(body, StandardCharsets.UTF_8));
            }
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
        final EventResult eventResult = acquireEventResult();

        try {
            // 耗时统计 - 序列化
            long serializeStart = System.nanoTime();
            byte[] body = serializer.serialize(eventModel, eventModel.getSerializeType());
            PerformanceMonitor.record("kafka.serialize", System.nanoTime() - serializeStart);

            // 使用预计算的defaultTopic
            String topic = eventModel.getTopic();
            if (topic == null || topic.isEmpty()) {
                topic = defaultTopic;
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
                    // 归还EventResult到池中
                    releaseEventResult(eventResult);
                });
            } else {
                RecordMetadata metadata = producer.send(record).get();
                PerformanceMonitor.record("kafka.send", System.nanoTime() - sendStart);
                eventResult.setMessageId(String.valueOf(metadata.offset()));
                eventResult.setTopic(topic);
                if (eventCallback != null) {
                    eventCallback.onSuccess(eventResult);
                }
                // 归还EventResult到池中
                releaseEventResult(eventResult);
            }
        } catch (Exception e) {
            if (!performanceMode) {
                log.warn("{} Publish message exception: {}", getEventBusType().getTypeName(), e.getMessage());
            }
            releaseEventResult(eventResult);
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
