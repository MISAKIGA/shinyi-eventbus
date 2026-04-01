package com.shinyi.eventbus.registry;

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
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
 *
 * 架构说明：
 * - ProducerHandler: Kafka生产者封装，处理消息发送和批量发送
 * - ConsumerHandler: Kafka消费者封装，处理消息消费和EOS
 * - EosOffsetManager: EOS语义下的offset管理
 * - EventResultPool: EventResult对象池，减少GC压力
 */
@Slf4j
public class OptimizedKafkaMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final KafkaConnectConfig kafkaConnectConfig;

    private final ProducerHandler producerHandler;
    private final ConsumerHandler consumerHandler;
    protected final Serializer serializer = new BaseSerializer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    // 预计算的默认值
    private String defaultTopic;
    private boolean performanceMode = false;

    // 自动刷新计数器 - 每flushInterval条消息自动flush一次
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private boolean autoFlush = true;
    private int flushInterval = 1000;

    // EOS: Offset tracking for manual commit
    private final EosOffsetManager eosOffsetManager;

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

        // 初始化组件
        this.producerHandler = new ProducerHandler();
        this.consumerHandler = new ConsumerHandler();
        this.eosOffsetManager = new EosOffsetManager();
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

        producerHandler.init(kafkaConnectConfig);

        // 预计算defaultTopic
        this.defaultTopic = kafkaConnectConfig.getTopic();

        // 从配置读取自动刷新参数
        this.autoFlush = kafkaConnectConfig.isAutoFlush();
        this.flushInterval = kafkaConnectConfig.getFlushInterval();

        if (performanceMode) {
            log.info("OptimizedKafkaMqEventListenerRegistry initialized in PERFORMANCE MODE");
            PerformanceMonitor.enable();
        } else {
            log.info("Kafka Producer initialized for {} with security protocol: {}",
                    kafkaConnectConfig.getBootstrapServers(), kafkaConnectConfig.getSecurityProtocol());
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
                .map(l -> CompletableFuture.runAsync(() -> consumerHandler.initConsumer(l, defaultTopic, kafkaConnectConfig, eosOffsetManager, this::deserialize)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
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
        producerHandler.publish(eventModel, defaultTopic, autoFlush, flushInterval, pendingCount, performanceMode,
                (er) -> acquireEventResult(er),
                (er) -> releaseEventResult(er),
                registryBeanName, getEventBusType());
    }

    @Override
    public void publishBatch(List<T> events, BatchEventCallback callback) {
        producerHandler.publishBatch(events, defaultTopic, performanceMode,
                (er) -> acquireEventResult(er),
                (er) -> releaseEventResult(er),
                callback);
    }

    @Override
    public void close() throws Exception {
        // EOS: Commit pending offsets for all consumers before shutdown and cleanup
        consumerHandler.shutdown(eosOffsetManager, performanceMode);

        if (producerHandler != null) {
            producerHandler.close();
        }
    }

    /**
     * Flush the producer buffer to ensure all pending messages are sent.
     * 对齐 kafka-demo：每 N 条消息 flush 一次，让 broker 分批处理
     */
    @Override
    public void flush() {
        producerHandler.flush();
    }

    // ==================== Inner Classes ====================

    /**
     * EventResult对象池 - 复用EventResult减少GC压力
     */
    private static final int EVENT_RESULT_POOL_SIZE = 1000;
    private final Queue<EventResult> eventResultPool = new ConcurrentLinkedQueue<>();
    private final Object poolLock = new Object();

    /**
     * 从池中获取EventResult，或创建新的
     */
    private EventResult acquireEventResult(EventResult reuse) {
        if (!performanceMode) {
            return reuse;
        }
        synchronized (poolLock) {
            EventResult result = eventResultPool.poll();
            if (result == null) {
                return new EventResult();
            }
            result.reset();
            return result;
        }
    }

    private EventResult acquireEventResult() {
        return acquireEventResult(new EventResult());
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
                result.reset();
                eventResultPool.offer(result);
            }
        }
    }

    /**
     * 初始化对象池
     */
    private void initEventResultPool() {
        if (performanceMode) {
            for (int i = 0; i < EVENT_RESULT_POOL_SIZE; i++) {
                eventResultPool.offer(new EventResult());
            }
        }
    }

    /**
     * Parse comma-separated topic string into a list.
     * @param topics comma-separated topic string (e.g., "topic1,topic2,topic3")
     * @return list of trimmed, non-empty topics
     */
    static List<String> parseTopics(String topics) {
        if (topics == null || topics.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(topics.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .collect(Collectors.toList());
    }

    // ==================== ProducerHandler ====================

    /**
     * Kafka生产者处理器
     * 封装所有生产者相关的操作
     */
    private class ProducerHandler {
        private KafkaProducer<String, byte[]> producer;

        void init(KafkaConnectConfig config) {
            Properties producerProps = config.toProducerProperties();
            this.producer = new KafkaProducer<>(producerProps);
        }

        void publish(T eventModel, String defaultTopic, boolean autoFlush, int flushInterval,
                     AtomicInteger pendingCount, boolean performanceMode,
                     java.util.function.Function<EventResult, EventResult> acquire,
                     java.util.function.Consumer<EventResult> release,
                     String registryBeanName, EventBusType eventBusType) {
            final EventCallback eventCallback = eventModel.getEventCallback();
            final EventResult eventResult = acquire.apply(new EventResult());

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
                        release.accept(eventResult);
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
                    release.accept(eventResult);

                    // 自动刷新 - 每flushInterval条消息刷新一次缓冲区
                    if (autoFlush && pendingCount.incrementAndGet() % flushInterval == 0) {
                        producer.flush();
                    }
                }
            } catch (Exception e) {
                if (!performanceMode) {
                    log.warn("{} Publish message exception: {}", eventBusType.getTypeName(), e.getMessage());
                }
                release.accept(eventResult);
                if (eventCallback != null) {
                    eventCallback.onFailure(eventResult, e);
                } else {
                    throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_ERROR, e.getMessage());
                }
            }
        }

        void publishBatch(List<T> events, String defaultTopic, boolean performanceMode,
                          java.util.function.Function<EventResult, EventResult> acquire,
                          java.util.function.Consumer<EventResult> release,
                          BatchEventCallback callback) {
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
                EventResult result = acquire.apply(new EventResult());
                results.add(result);

                try {
                    byte[] body = serializer.serialize(event, event.getSerializeType());
                    String topic = event.getTopic();
                    if (topic == null || topic.isEmpty()) {
                        topic = defaultTopic;
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
                        release.accept(result);
                    });
                } catch (Exception e) {
                    errors.add(e);
                    latch.countDown();
                    release.accept(result);
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
                } finally {
                    // 释放所有EventResult到池中
                    for (EventResult result : results) {
                        release.accept(result);
                    }
                }
            });
        }

        void flush() {
            if (producer != null) {
                producer.flush();
            }
        }

        void close() {
            if (producer != null) {
                try {
                    producer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== ConsumerHandler ====================

    /**
     * Kafka消费者处理器
     * 封装所有消费者相关的操作
     */
    private class ConsumerHandler {
        private final Set<KafkaConsumer<String, byte[]>> consumerSet = new ConcurrentHashSet<>();
        private final Set<ExecutorService> executorSet = new ConcurrentHashSet<>();
        private ExecutorService parallelExecutor;  // parallel processing thread pool

        void initConsumer(com.shinyi.eventbus.EventListener<T> listener, String defaultTopic,
                          KafkaConnectConfig config, EosOffsetManager eosManager,
                          DeserializeFunction<T> deserializeFn) {
            // EOS: Get EOS settings - check listener annotation first, fall back to global config
            final boolean eosEnabled = listener.exactlyOnce() || config.isEnableManualCommit();
            final int commitBatchSize = listener.commitBatchSize() > 0
                ? listener.commitBatchSize()
                : config.getCommitBatchSize();

            Properties consumerProps = config.toConsumerProperties(eosEnabled);
            consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, listener.group());

            KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
            consumerSet.add(consumer);

            String topics = listener.topic();
            if (topics == null || topics.isEmpty()) {
                topics = defaultTopic;
            }
            List<String> topicList = parseTopics(topics);
            consumer.subscribe(topicList);

            // Initialize parallel processing thread pool
            if (parallelExecutor == null) {
                int configuredThreads = config.getConsumerThreads();

                // Try to get partition count for intelligent thread balancing
                int partitionCount = 0;
                try {
                    List<PartitionInfo> partitions = consumer.partitionsFor(
                        topicList.get(0), Duration.ofSeconds(5));
                    if (partitions != null && !partitions.isEmpty()) {
                        partitionCount = partitions.size();
                    }
                } catch (Exception e) {
                    // Unable to get partition count, use configured value
                }

                int threads;
                int cpuCores = Runtime.getRuntime().availableProcessors();

                if (config.isAutoDetectConsumerThreads() && partitionCount > 0) {
                    // Intelligent balancing strategy:
                    if (partitionCount <= cpuCores) {
                        threads = Math.min(partitionCount, configuredThreads > 0 ? configuredThreads : cpuCores);
                    } else {
                        int balancedThreads = Math.min(cpuCores * 4, Math.min(partitionCount, 32));
                        threads = configuredThreads > 0
                            ? Math.min(configuredThreads, balancedThreads)
                            : balancedThreads;
                    }
                    if (!performanceMode) {
                        log.info("Kafka parallel consumer: detected {} partitions, {} CPU cores, using {} threads (balanced)",
                            partitionCount, cpuCores, threads);
                    }
                } else {
                    threads = configuredThreads <= 0 ? cpuCores : configuredThreads;
                    threads = Math.min(threads, 32);
                }

                parallelExecutor = Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "kafka-parallel-consumer");
                    t.setDaemon(true);
                    return t;
                });
                executorSet.add(parallelExecutor);
            }

            String threadName = topicList.size() > 1
                ? "kafka-consumer-multi-" + topicList.get(0)
                : "kafka-consumer-" + topicList.get(0);
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName));
            executorSet.add(executor);

            final com.shinyi.eventbus.EventListener<T> finalListener = listener;
            executor.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                            if (records == null || records.isEmpty()) {
                                continue;
                            }

                            // Group records by TopicPartition for parallel processing
                            Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> recordsByPartition =
                                records.partitions().stream()
                                    .collect(Collectors.toMap(
                                        tp -> tp,
                                        tp -> records.records(tp)
                                    ));

                            // Create parallel tasks for each partition
                            CountDownLatch latch = new CountDownLatch(recordsByPartition.size());

                            recordsByPartition.forEach((tp, partitionRecords) -> {
                                parallelExecutor.submit(() -> {
                                    try {
                                        for (ConsumerRecord<String, byte[]> record : partitionRecords) {
                                            processRecord(record, finalListener, eosEnabled, eosManager, consumer, commitBatchSize, deserializeFn);
                                        }
                                    } finally {
                                        latch.countDown();
                                    }
                                });
                            });

                            // Wait for all partitions to complete processing
                            try {
                                latch.await(5, TimeUnit.MINUTES);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } catch (WakeupException e) {
                            break;
                        }
                    }
                } finally {
                    // EOS: Commit pending offsets before shutdown and cleanup
                    if (eosEnabled) {
                        eosManager.commitPendingOffsets(consumer, true);  // true = remove after commit
                    }
                    consumer.close();
                }
            });

            if (!performanceMode) {
                log.info("Kafka consumer started for topics: {}, group: {}", topicList, finalListener.group());
            }
        }

        private void processRecord(ConsumerRecord<String, byte[]> record,
                                  com.shinyi.eventbus.EventListener<T> listener,
                                  boolean eosEnabled,
                                  EosOffsetManager eosManager,
                                  KafkaConsumer<String, byte[]> consumer,
                                  int commitBatchSize,
                                  DeserializeFunction<T> deserializeFn) {
            try {
                if (record.value() == null || record.value().length == 0) {
                    if (!performanceMode) {
                        log.warn("Message body is empty, skipping. offset={}", record.offset());
                    }
                    return;
                }
                EventModel<?> eventModel = deserializeFn.apply(record.value(), record.offset() + "", listener);
                listener.onMessage((T) eventModel);
                // EOS: Track offset
                if (eosEnabled) {
                    eosManager.trackOffsetAndCommit(consumer, record, commitBatchSize);
                }
                // Record metrics
                MetricsHolder.increment(registryBeanName, record.topic(), "events.consumed", 1);
            } catch (Exception e) {
                MetricsHolder.increment(registryBeanName, record.topic(), "events.failed", 1);
                if (!performanceMode) {
                    log.warn("Message processing failed: " + e.getMessage(), e);
                }
            }
        }

        void shutdown(EosOffsetManager eosManager, boolean performanceMode) {
            // EOS: Commit pending offsets for all consumers before shutdown and cleanup
            for (KafkaConsumer<String, byte[]> consumer : consumerSet) {
                eosManager.commitPendingOffsets(consumer, true);  // true = remove after commit
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
        }
    }

    // ==================== EosOffsetManager ====================

    /**
     * EOS (Exactly-Once Semantics) Offset管理器
     * 处理EOS语义下的offset提交
     */
    private class EosOffsetManager {
        private final Map<KafkaConsumer<String, byte[]>, OffsetCommitState> offsetStates = new ConcurrentHashMap<>();
        private final Object commitLock = new Object();

        /**
         * EOS: Track offset and commit when batch size reached
         */
        void trackOffsetAndCommit(KafkaConsumer<String, byte[]> consumer,
                                  ConsumerRecord<String, byte[]> record,
                                  int batchSize) {
            OffsetCommitState state = offsetStates.computeIfAbsent(consumer, k -> new OffsetCommitState());

            TopicPartition tp = new TopicPartition(record.topic(), record.partition());
            state.pendingOffsets.put(tp, new OffsetAndMetadata(record.offset() + 1));
            int count = state.processedCount.incrementAndGet();

            if (count >= batchSize) {
                synchronized (commitLock) {
                    if (state.processedCount.get() >= batchSize) {
                        commitPendingOffsetsInternal(consumer, state);
                    }
                }
            }
        }

        /**
         * EOS: Commit pending offsets to Kafka
         */
        void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer) {
            commitPendingOffsets(consumer, false);
        }

        /**
         * EOS: Internal commit method - caller must hold commitLock
         */
        private void commitPendingOffsetsInternal(KafkaConsumer<String, byte[]> consumer, OffsetCommitState state) {
            if (state != null && !state.pendingOffsets.isEmpty()) {
                try {
                    consumer.commitSync(new HashMap<>(state.pendingOffsets));
                    if (!performanceMode) {
                        log.debug("EOS: Committed offsets for {} partitions", state.pendingOffsets.size());
                    }
                    state.pendingOffsets.clear();
                    state.processedCount.set(0);
                } catch (Exception e) {
                    if (!performanceMode) {
                        log.error("EOS: Failed to commit offsets: " + e.getMessage(), e);
                    }
                }
            }
        }

        /**
         * EOS: Commit pending offsets to Kafka
         * @param removeAfterCommit if true, remove the consumer entry from offsetStates after committing
         */
        void commitPendingOffsets(KafkaConsumer<String, byte[]> consumer, boolean removeAfterCommit) {
            synchronized (commitLock) {
                OffsetCommitState state = offsetStates.get(consumer);
                if (state != null && !state.pendingOffsets.isEmpty()) {
                    try {
                        consumer.commitSync(new HashMap<>(state.pendingOffsets));
                        if (!performanceMode) {
                            log.debug("EOS: Committed offsets for {} partitions", state.pendingOffsets.size());
                        }
                        state.pendingOffsets.clear();
                        state.processedCount.set(0);
                    } catch (Exception e) {
                        if (!performanceMode) {
                            log.error("EOS: Failed to commit offsets: " + e.getMessage(), e);
                        }
                    }
                }
                if (removeAfterCommit) {
                    offsetStates.remove(consumer);
                }
            }
        }
    }

    // ==================== OffsetCommitState ====================

    /**
     * EOS: Offset tracking state per consumer
     */
    private static class OffsetCommitState {
        Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new ConcurrentHashMap<>();
        AtomicInteger processedCount = new AtomicInteger(0);
    }

    // ==================== Functional Interface ====================

    @FunctionalInterface
    interface DeserializeFunction<T> {
        EventModel<?> apply(byte[] body, String consumerTag, com.shinyi.eventbus.EventListener<T> listener);
    }
}
