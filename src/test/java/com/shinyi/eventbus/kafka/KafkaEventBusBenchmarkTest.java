package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.registry.KafkaMqEventListenerRegistry;
import com.shinyi.eventbus.registry.OptimizedKafkaMqEventListenerRegistry;
import com.shinyi.eventbus.serialize.Serializer;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.support.EventListenerRegistryManager;
import com.shinyi.eventbus.monitor.PerformanceMonitor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka EventBus Benchmark Test
 *
 * Tests the performance of shinyi-eventbus Kafka integration using the eventbus API:
 * 1. Baseline configuration (default settings)
 * 2. Optimized configuration (compression, batching)
 * 3. EOS configuration (idempotent producer + manual commit)
 *
 * Uses the eventbus API (EventListenerRegistryManager.publish and EventListener interface)
 * NOT direct KafkaClient.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaEventBusBenchmarkTest {

    private static final String TOPIC = "benchmark-test-topic";
    private static final int MESSAGE_COUNT = 100_000;
    private static final int MESSAGE_SIZE = 1024; // 1KB

    private KafkaContainer kafkaContainer;
    private Network network;
    private String bootstrapServers;

    // Test results storage
    private final List<BenchmarkResult> results = new CopyOnWriteArrayList<>();

    // Current Kafka registry reference for flush operations
    private KafkaMqEventListenerRegistry<EventModel<?>> currentKafkaRegistry;

    @BeforeAll
    void startKafka() {
        network = Network.newNetwork();
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withExposedPorts(9092, 9093);

        Startables.deepStart(kafkaContainer).join();
        bootstrapServers = kafkaContainer.getBootstrapServers();
        log.info("Kafka started at: {}", bootstrapServers);
    }

    @AfterAll
    void stopKafka() {
        if (kafkaContainer != null) {
            kafkaContainer.close();
        }
        if (network != null) {
            network.close();
        }
    }

    /**
     * Benchmark test event class with data integrity validation
     */
    @Data
    public static class BenchmarkEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        private long sequence;
        private long timestamp;
        private String payload; // Actual data for validation
        private String checksum; // MD5 checksum for integrity verification

        public static BenchmarkEvent create(long sequence, int size) {
            BenchmarkEvent event = new BenchmarkEvent();
            event.setSequence(sequence);
            event.setTimestamp(System.currentTimeMillis());

            // Create deterministic payload based on sequence to enable validation
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("MSG-%010d-", sequence));
            while (sb.length() < size - 40) { // Leave space for checksum
                sb.append(String.format("%08d", sequence * 31 + sb.length()));
            }
            event.setPayload(sb.toString());

            // Calculate checksum for data integrity validation
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                String dataToHash = event.getSequence() + "-" + event.getPayload();
                byte[] hash = md.digest(dataToHash.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                event.setChecksum(hexString.toString());
            } catch (Exception e) {
                event.setChecksum("ERROR");
            }

            return event;
        }

        public boolean validateIntegrity() {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                String dataToHash = this.sequence + "-" + this.payload;
                byte[] hash = md.digest(dataToHash.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                return hexString.toString().equals(this.checksum);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Benchmark result data class
     */
    @Data
    public static class BenchmarkResult {
        private String testName;
        private String configuration;
        private int messageCount;
        private long successCount;
        private long failureCount;
        private long totalBytes;
        private long durationMs;
        private double throughputMsgPerSec;
        private double mbPerSec;
        private double avgLatencyMs;
        private double p50LatencyMs;
        private double p90LatencyMs;
        private double p99LatencyMs;
        private int maxPollRecords;
        private boolean exactlyOnce;
        private long dataIntegrityFailures;
    }

    // ==================== Baseline Configuration Test ====================

    @Test
    @DisplayName("Benchmark 1: Baseline Producer (no optimization)")
    void testBaselineProducerPerformance() throws Exception {
        log.info("\n========== BASELINE PRODUCER BENCHMARK ==========");

        // Create baseline config (default settings)
        KafkaConnectConfig config = createBaselineConfig();

        BenchmarkResult result = runProducerBenchmark(
                "Baseline Producer",
                config,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                false // not EOS
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
        assertEquals(0, result.getDataIntegrityFailures(), "No data integrity failures");
    }

    @Test
    @DisplayName("Benchmark 2: Optimized Producer (compression + batching)")
    void testOptimizedProducerPerformance() throws Exception {
        log.info("\n========== OPTIMIZED PRODUCER BENCHMARK ==========");

        // Create optimized config
        KafkaConnectConfig config = createOptimizedConfig();

        BenchmarkResult result = runProducerBenchmark(
                "Optimized Producer",
                config,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                false // not EOS
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
    }

    @Test
    @DisplayName("Benchmark 3: EOS Producer (idempotent)")
    void testEosProducerPerformance() throws Exception {
        log.info("\n========== EOS PRODUCER BENCHMARK ==========");

        // Create EOS config with idempotence enabled
        KafkaConnectConfig config = createEosConfig();

        BenchmarkResult result = runProducerBenchmark(
                "EOS Producer",
                config,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                true // EOS enabled
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
        assertTrue(result.isExactlyOnce(), "Should have exactly-once semantics enabled");
    }

    @Test
    @DisplayName("Benchmark 4: Optimized Consumer")
    void testOptimizedConsumerPerformance() throws Exception {
        log.info("\n========== OPTIMIZED CONSUMER BENCHMARK ==========");

        // First, send messages with optimized producer
        KafkaConnectConfig producerConfig = createOptimizedConfig();
        runProducerBenchmark("Pre-test Producer", producerConfig, MESSAGE_COUNT, MESSAGE_SIZE, false);

        // Wait for messages to be available
        Thread.sleep(2000);

        // Now benchmark consumer
        KafkaConnectConfig consumerConfig = createOptimizedConfig();
        consumerConfig.setMaxPollRecords(5000);

        BenchmarkResult result = runConsumerBenchmark(
                "Optimized Consumer",
                consumerConfig,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                false
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully consume messages");
    }

    @Test
    @DisplayName("Benchmark 5: EOS Consumer (manual commit)")
    void testEosConsumerPerformance() throws Exception {
        log.info("\n========== EOS CONSUMER BENCHMARK ==========");

        // First, send messages with EOS producer
        KafkaConnectConfig producerConfig = createEosConfig();
        runProducerBenchmark("Pre-test EOS Producer", producerConfig, MESSAGE_COUNT, MESSAGE_SIZE, true);

        // Wait for messages to be available
        Thread.sleep(2000);

        // Now benchmark EOS consumer with manual commit
        KafkaConnectConfig consumerConfig = createEosConfig();

        BenchmarkResult result = runConsumerBenchmark(
                "EOS Consumer",
                consumerConfig,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                true // EOS enabled
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully consume messages");
        assertTrue(result.isExactlyOnce(), "Should have exactly-once semantics enabled");
    }

    @Test
    @DisplayName("Benchmark Results Comparison")
    void testPrintResultsComparison() {
        log.info("\n========== BENCHMARK RESULTS COMPARISON ==========");

        if (results.isEmpty()) {
            log.warn("No benchmark results to compare!");
            return;
        }

        printComparisonTable();
    }

    // ==================== RAW Mode Benchmark (Aligned with kafka-demo) ====================

    /**
     * RAW mode: Bypasses JSON serialization by sending raw byte[] payload directly.
     * This is the fair comparison against kafka-demo's direct byte[] approach.
     */
    @Test
    @DisplayName("Benchmark 6: RAW Producer (no JSON, direct byte[] payload)")
    void testRawProducerPerformance() throws Exception {
        // 启用性能监控
        PerformanceMonitor.enable();
        PerformanceMonitor.reset();

        log.info("\n========== RAW PRODUCER BENCHMARK (kafka-demo aligned) ==========");

        // Create kafka-demo aligned config
        KafkaConnectConfig config = createKafkaDemoAlignedConfig();

        BenchmarkResult result = runRawProducerBenchmark(
                "RAW Producer (kafka-demo aligned)",
                config,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                false
        );

        results.add(result);
        printResult(result);

        // 打印性能监控报告
        System.out.println(PerformanceMonitor.getReport());

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
        assertEquals(0, result.getDataIntegrityFailures(), "No data integrity failures");
    }

    /**
     * Multi-threaded RAW producer using 4 producer threads.
     * Each thread has its own producer for true parallelism.
     */
    @Test
    @DisplayName("Benchmark 7: Multi-Threaded RAW Producer (4 threads)")
    void testMultiThreadedRawProducerPerformance() throws Exception {
        log.info("\n========== MULTI-THREADED RAW PRODUCER BENCHMARK ==========");

        // Create kafka-demo aligned config
        KafkaConnectConfig config = createKafkaDemoAlignedConfig();

        BenchmarkResult result = runMultiThreadedRawProducerBenchmark(
                "Multi-Threaded RAW Producer (4 threads)",
                config,
                MESSAGE_COUNT / 4, // 25K messages per thread
                MESSAGE_SIZE,
                4, // 4 threads
                false
        );

        results.add(result);
        printResult(result);

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
        assertEquals(0, result.getDataIntegrityFailures(), "No data integrity failures");
    }

    /**
     * Benchmark 8: Optimized RAW Producer with object pooling and disabled logging
     */
    @Test
    @DisplayName("Benchmark 8: Optimized RAW Producer (pooling + no logging)")
    void testOptimizedRawProducerPerformance() throws Exception {
        // Enable performance mode via system property
        System.setProperty("com.shinyi.eventbus.performance.optimized", "true");
        PerformanceMonitor.enable();
        PerformanceMonitor.reset();

        log.info("\n========== OPTIMIZED RAW PRODUCER BENCHMARK (object pooling + no logging) ==========");

        KafkaConnectConfig config = createKafkaDemoAlignedConfig();

        BenchmarkResult result = runOptimizedRawProducerBenchmark(
                "Optimized RAW Producer (pooled)",
                config,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                false
        );

        results.add(result);
        printResult(result);
        System.out.println(PerformanceMonitor.getReport());

        // Reset system property
        System.clearProperty("com.shinyi.eventbus.performance.optimized");

        // Assertions
        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
    }

    // ==================== Helper Methods ====================

    private KafkaConnectConfig createBaselineConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        config.setGroupId("baseline-consumer-group");
        // Default settings - no optimization
        config.setAcks("1");
        config.setRetries(3);
        config.setBatchSize(16384);
        config.setLingerMs(1);
        config.setBufferMemory(33554432);
        config.setMaxPollRecords(500);
        return config;
    }

    private KafkaConnectConfig createOptimizedConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        config.setGroupId("optimized-consumer-group");
        // Optimized settings (P0.2)
        config.setAcks("1");
        config.setRetries(3);
        config.setBatchSize(65536);         // 64KB batch
        config.setLingerMs(10);             // 10ms linger
        config.setBufferMemory(67108864);   // 64MB buffer
        config.setCompressionType("snappy"); // Snappy compression
        config.setMaxPollRecords(5000);     // Industry optimal
        config.setFetchMinBytes(1024);
        config.setFetchMaxWaitMs(1000);
        config.setMaxPartitionFetchBytes(1048576);
        return config;
    }

    private KafkaConnectConfig createEosConfig() {
        KafkaConnectConfig config = createOptimizedConfig();
        config.setGroupId("eos-consumer-group");
        // EOS settings (P0.3)
        config.setEnableIdempotence(true);    // Idempotent producer
        config.setEnableManualCommit(false);   // We'll use auto commit for this test
        config.setCommitBatchSize(100);
        return config;
    }

    /**
     * Create config fully aligned with kafka-demo's Optimized Producer settings.
     *
     * 对齐原因：
     * 1. acks="all" - kafka-demo 所有测试都用 acks=all，这是启用 idempotence 的前提
     *    - acks=1 只等 leader 确认，可能丢数据
     *    - acks=all 等所有 ISR 确认，数据更安全
     *
     * 2. enable.idempotence=true - Exactly-Once 语义保证
     *    - 防止消息重复发送
     *    - Kafka 推荐在生产环境启用
     *
     * 3. retries=MAX_VALUE - Kafka 推荐值
     *    - 确保网络抖动时消息能重试发送
     *    - 配合 idempotence 避免消息重复
     *
     * 4. 中间 flush 策略 - 每 1000 条 flush 一次
     *    - 让 broker 分批处理，而不是最后一次性 flush 全部
     *    - 平衡延迟和吞吐量
     *
     * 对齐后的目标：达到 kafka-demo Optimized 的 50,000 msg/s 水平
     */
    private KafkaConnectConfig createKafkaDemoAlignedConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        config.setGroupId("raw-consumer-group");
        // kafka-demo Optimized Producer 对齐配置
        config.setAcks("all");                    // 关键：对齐 kafka-demo，全部等待 ISR 确认
        config.setRetries(Integer.MAX_VALUE);     // Kafka 推荐值，确保重试足够
        config.setBatchSize(65536);               // 64KB batch
        config.setLingerMs(10);                   // 10ms linger - Kafka内部 batching
        config.setBufferMemory(67108864);         // 64MB buffer
        config.setCompressionType("snappy");      // Snappy 压缩
        config.setMaxPollRecords(5000);
        config.setFetchMinBytes(1024);
        config.setFetchMaxWaitMs(1000);
        config.setMaxPartitionFetchBytes(1048576);
        // 关闭 autoFlush - 依赖 Kafka 内部 batching 提升吞吐
        config.setAutoFlush(false);
        config.setFlushInterval(Integer.MAX_VALUE); // 几乎不触发
        return config;
    }

    /**
     * Run producer benchmark using the eventbus API
     */
    private BenchmarkResult runProducerBenchmark(String testName, KafkaConnectConfig config,
                                                  int messageCount, int messageSize,
                                                  boolean exactlyOnce) throws Exception {
        log.info("Starting producer benchmark: {} with config: {}", testName, config);

        // Create registry and manager for eventbus API
        EventListenerRegistryManager registryManager = createRegistryManager(config);

        // Start the registry manager (initializes producers/consumers)
        registryManager.start();

        // Prepare benchmark tracking
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicLong dataIntegrityFailures = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        try {
            // Use eventbus API to publish messages
            for (int i = 0; i < messageCount; i++) {
                final int messageId = i;
                long messageStartTime = System.nanoTime();

                BenchmarkEvent event = BenchmarkEvent.create(messageId, messageSize);

                EventModel<BenchmarkEvent> eventModel = EventModel.build(
                        TOPIC,
                        event,
                        String.valueOf(messageId),
                        true, // async
                        "JSON",
                        new EventCallback() {
                            @Override
                            public void onSuccess(EventResult eventResult) {
                                successCount.incrementAndGet();
                                totalBytes.addAndGet(serializeSize(event));
                                latencies.add((System.nanoTime() - messageStartTime) / 1_000_000);

                                // Validate data integrity
                                if (!event.validateIntegrity()) {
                                    dataIntegrityFailures.incrementAndGet();
                                    log.error("Data integrity failure for message: {}", messageId);
                                }
                                latch.countDown();
                            }

                            @Override
                            public void onFailure(EventResult eventResult, Throwable e) {
                                failureCount.incrementAndGet();
                                log.error("Failed to send message {}: {}", messageId, e.getMessage());
                                latch.countDown();
                            }
                        }
                );

                registryManager.publish(EventBusType.KAFKA, eventModel);

                if (i > 0 && i % 10000 == 0) {
                    log.info("Sent {} / {} messages...", i, messageCount);
                }
            }

            // Wait for all messages to be sent (max 5 minutes)
            boolean completed = latch.await(5, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("Benchmark did not complete within timeout. Sent: {}/{}", successCount.get(), messageCount);
            }

        } finally {
            registryManager.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateProducerResult(testName, config, messageCount, successCount, failureCount,
                totalBytes, duration, latencies, exactlyOnce, dataIntegrityFailures.get());
    }

    /**
     * Run consumer benchmark using @EventBusListener
     */
    private BenchmarkResult runConsumerBenchmark(String testName, KafkaConnectConfig config,
                                                  int expectedMessages, int messageSize,
                                                  boolean exactlyOnce) throws Exception {
        log.info("Starting consumer benchmark: {} with config: {}", testName, config);

        // This is a simplified consumer benchmark
        // In a real scenario, we'd need to properly set up the consumer with listener

        AtomicLong consumedCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicLong dataIntegrityFailures = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        // For this test, we create a simple consumer simulation
        // In real implementation, this would use the @EventBusListener pattern
        Properties consumerProps = config.toConsumerProperties();
        org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]> consumer =
                new org.apache.kafka.clients.consumer.KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC));

        int maxPolls = 100;
        int pollCount = 0;
        Set<String> seenKeys = new ConcurrentHashMap().newKeySet();

        try {
            while (consumedCount.get() < expectedMessages && pollCount < maxPolls) {
                org.apache.kafka.clients.consumer.ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                for (org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record : records) {
                    long messageTime = System.currentTimeMillis() - record.timestamp();
                    latencies.add(messageTime);
                    consumedCount.incrementAndGet();
                    totalBytes.addAndGet(record.value() != null ? record.value().length : 0);

                    // Check for duplicates (for EOS validation)
                    if (exactlyOnce) {
                        if (!seenKeys.add(record.key())) {
                            log.warn("Duplicate message detected: {}", record.key());
                        }
                    }

                    if (consumedCount.get() % 10000 == 0) {
                        log.info("Consumed {} / {} messages...", consumedCount.get(), expectedMessages);
                    }
                }
                pollCount++;

                if (records.isEmpty() && consumedCount.get() >= expectedMessages) {
                    break;
                }
            }
        } finally {
            consumer.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateConsumerResult(testName, config, expectedMessages, consumedCount,
                totalBytes, duration, latencies, exactlyOnce, dataIntegrityFailures.get());
    }

    /**
     * RAW mode producer benchmark - bypasses JSON serialization by using byte[] payload directly.
     * This is the fair comparison against kafka-demo's direct byte[] approach.
     *
     * 关键优化：对齐 kafka-demo 的 flush 策略
     * - kafka-demo 每 1000 条 flush 一次，让 broker 分批处理
     * - 而不是等到最后一次性 flush 全部 10 万条
     */
    private BenchmarkResult runRawProducerBenchmark(String testName, KafkaConnectConfig config,
                                                    int messageCount, int messageSize,
                                                    boolean exactlyOnce) throws Exception {
        log.info("Starting RAW producer benchmark: {} with kafka-demo aligned config", testName);

        // Create registry and manager for eventbus API
        EventListenerRegistryManager registryManager = createRegistryManager(config);

        // Start the registry manager (initializes producers/consumers)
        registryManager.start();

        // Prepare benchmark tracking
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicLong dataIntegrityFailures = new AtomicLong(0);

        // Use RAW serialization - directly send byte[] payload without JSON
        final Serializer serializer = new BaseSerializer();

        long startTime = System.currentTimeMillis();

        try {
            for (int i = 0; i < messageCount; i++) {
                final int messageId = i;
                long messageStartTime = System.nanoTime();

                // Create raw byte[] payload directly (no JSON, no EventModel wrapper overhead)
                byte[] rawPayload = generateRawPayload(messageId, messageSize);

                // Create EventModel with RAW serialization type and byte[] entity
                EventModel<byte[]> eventModel = EventModel.build(
                        TOPIC,
                        rawPayload,  // byte[] entity - RAW mode sends this directly
                        String.valueOf(messageId),
                        true, // async
                        "RAW",  // Use RAW mode to bypass JSON serialization
                        new EventCallback() {
                            @Override
                            public void onSuccess(EventResult eventResult) {
                                successCount.incrementAndGet();
                                totalBytes.addAndGet(rawPayload.length);
                                latencies.add((System.nanoTime() - messageStartTime) / 1_000_000);
                                latch.countDown();
                            }

                            @Override
                            public void onFailure(EventResult eventResult, Throwable e) {
                                failureCount.incrementAndGet();
                                log.error("Failed to send message {}: {}", messageId, e.getMessage());
                                latch.countDown();
                            }
                        }
                );

                registryManager.publish(EventBusType.KAFKA, eventModel);

                // 对齐 kafka-demo：每 1000 条 flush 一次，让 broker 分批处理
                // 而不是等到最后一次性 flush 全部 10 万条
                if (i > 0 && i % 1000 == 0) {
                    currentKafkaRegistry.flush();
                }

                if (i > 0 && i % 10000 == 0) {
                    log.info("Sent {} / {} messages...", i, messageCount);
                }
            }

            // Wait for all messages to be sent (max 5 minutes)
            boolean completed = latch.await(5, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("Benchmark did not complete within timeout. Sent: {}/{}", successCount.get(), messageCount);
            }

        } finally {
            registryManager.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateProducerResult(testName, config, messageCount, successCount, failureCount,
                totalBytes, duration, latencies, exactlyOnce, dataIntegrityFailures.get());
    }

    /**
     * Multi-threaded RAW producer benchmark using multiple producer threads.
     * Each thread has its own producer for true parallelism.
     */
    private BenchmarkResult runMultiThreadedRawProducerBenchmark(String testName, KafkaConnectConfig config,
                                                                 int messagesPerThread, int messageSize,
                                                                 int threadCount, boolean exactlyOnce) throws Exception {
        log.info("Starting multi-threaded RAW producer benchmark: {} with {} threads", testName, threadCount);

        // Prepare benchmark tracking
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicLong dataIntegrityFailures = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(messagesPerThread * threadCount);

        long startTime = System.currentTimeMillis();

        try {
            // Launch multiple producer threads
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                Future<?> future = executor.submit(() -> {
                    try {
                        // Each thread creates its own registry manager and producer
                        EventListenerRegistryManager registryManager = createRegistryManager(config);
                        registryManager.start();

                        try {
                            for (int i = 0; i < messagesPerThread; i++) {
                                final int globalMessageId = threadId * messagesPerThread + i;
                                long messageStartTime = System.nanoTime();

                                // Create raw byte[] payload directly
                                byte[] rawPayload = generateRawPayload(globalMessageId, messageSize);

                                EventModel<byte[]> eventModel = EventModel.build(
                                        TOPIC,
                                        rawPayload,
                                        String.valueOf(globalMessageId),
                                        true,
                                        "RAW",
                                        new EventCallback() {
                                            @Override
                                            public void onSuccess(EventResult eventResult) {
                                                successCount.incrementAndGet();
                                                totalBytes.addAndGet(rawPayload.length);
                                                latencies.add((System.nanoTime() - messageStartTime) / 1_000_000);
                                                latch.countDown();
                                            }

                                            @Override
                                            public void onFailure(EventResult eventResult, Throwable e) {
                                                failureCount.incrementAndGet();
                                                log.error("Failed to send message {}: {}", globalMessageId, e.getMessage());
                                                latch.countDown();
                                            }
                                        }
                                );

                                registryManager.publish(EventBusType.KAFKA, eventModel);
                            }
                        } finally {
                            registryManager.close();
                        }
                    } catch (Exception e) {
                        log.error("Thread {} failed: {}", threadId, e.getMessage(), e);
                    }
                });
                futures.add(future);
            }

            // Wait for all threads to complete
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }

            // Wait for remaining callbacks
            latch.await(1, TimeUnit.MINUTES);

        } finally {
            executor.shutdownNow();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        int totalMessages = messagesPerThread * threadCount;

        return calculateProducerResult(testName, config, totalMessages, successCount, failureCount,
                totalBytes, duration, latencies, exactlyOnce, dataIntegrityFailures.get());
    }

    /**
     * Generate raw byte[] payload directly without JSON serialization.
     * This mirrors kafka-demo's generatePayload() approach.
     */
    private byte[] generateRawPayload(int messageId, int targetSize) {
        // Create deterministic payload based on messageId
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("MSG-%010d-", messageId));
        while (sb.length() < targetSize) {
            sb.append(String.format("%08d", messageId * 31 + sb.length()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Create optimized registry manager using object pooling and disabled logging
     */
    private EventListenerRegistryManager createOptimizedRegistryManager(KafkaConnectConfig config) {
        org.springframework.context.support.GenericApplicationContext ctx =
                new org.springframework.context.support.GenericApplicationContext();

        // Use OptimizedKafkaMqEventListenerRegistry with object pooling
        OptimizedKafkaMqEventListenerRegistry<EventModel<?>> kafkaRegistry =
                new OptimizedKafkaMqEventListenerRegistry<>(ctx, "kafka", config);
        kafkaRegistry.init();

        ctx.registerBean("kafkaEventListenerRegistry", EventListenerRegistry.class, () -> kafkaRegistry);
        ctx.registerBean(EventListenerRegistryManager.class);
        ctx.refresh();

        return ctx.getBean(EventListenerRegistryManager.class);
    }

    /**
     * Optimized RAW producer benchmark using object pooling
     */
    private BenchmarkResult runOptimizedRawProducerBenchmark(String testName, KafkaConnectConfig config,
                                                            int messageCount, int messageSize,
                                                            boolean exactlyOnce) throws Exception {
        log.info("Starting OPTIMIZED RAW producer benchmark: {} with object pooling", testName);

        // Create registry using optimized version
        EventListenerRegistryManager registryManager = createOptimizedRegistryManager(config);
        registryManager.start();

        // Prepare benchmark tracking
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(messageCount);

        long startTime = System.currentTimeMillis();

        try {
            for (int i = 0; i < messageCount; i++) {
                final int messageId = i;
                long messageStartTime = System.nanoTime();

                byte[] rawPayload = generateRawPayload(messageId, messageSize);

                EventModel<byte[]> eventModel = EventModel.build(
                        TOPIC,
                        rawPayload,
                        String.valueOf(messageId),
                        true, // async
                        "RAW",
                        new EventCallback() {
                            @Override
                            public void onSuccess(EventResult eventResult) {
                                successCount.incrementAndGet();
                                totalBytes.addAndGet(rawPayload.length);
                                latencies.add((System.nanoTime() - messageStartTime) / 1_000_000);
                                latch.countDown();
                            }

                            @Override
                            public void onFailure(EventResult eventResult, Throwable e) {
                                failureCount.incrementAndGet();
                                log.error("Failed to send message {}: {}", messageId, e.getMessage());
                                latch.countDown();
                            }
                        }
                );

                registryManager.publish(EventBusType.KAFKA, eventModel);

                if (i > 0 && i % 10000 == 0) {
                    log.info("Sent {} / {} messages...", i, messageCount);
                }
            }

            boolean completed = latch.await(5, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("Benchmark did not complete within timeout. Sent: {}/{}", successCount.get(), messageCount);
            }

        } finally {
            registryManager.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateProducerResult(testName, config, messageCount, successCount, failureCount,
                totalBytes, duration, latencies, exactlyOnce, 0);
    }

    private EventListenerRegistryManager createRegistryManager(KafkaConnectConfig config) {
        // Create a simple Spring application context for testing
        org.springframework.context.support.GenericApplicationContext ctx =
                new org.springframework.context.support.GenericApplicationContext();

        // Register Kafka registry
        KafkaMqEventListenerRegistry<EventModel<?>> kafkaRegistry =
                new KafkaMqEventListenerRegistry<>(ctx, "kafka", config);
        kafkaRegistry.init();

        // Save reference for flush operations
        this.currentKafkaRegistry = kafkaRegistry;

        ctx.registerBean("kafkaEventListenerRegistry", EventListenerRegistry.class, () -> kafkaRegistry);
        ctx.registerBean(EventListenerRegistryManager.class);
        ctx.refresh();

        return ctx.getBean(EventListenerRegistryManager.class);
    }

    private int serializeSize(BenchmarkEvent event) {
        try {
            return event.toString().getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            return MESSAGE_SIZE;
        }
    }

    private BenchmarkResult calculateProducerResult(String testName, KafkaConnectConfig config,
                                                    int messageCount, AtomicLong successCount,
                                                    AtomicLong failureCount, AtomicLong totalBytes,
                                                    long duration, ConcurrentLinkedQueue<Long> latencies,
                                                    boolean exactlyOnce, long dataIntegrityFailures) {
        BenchmarkResult result = new BenchmarkResult();
        result.setTestName(testName);
        result.setConfiguration(buildConfigDescription(config, exactlyOnce));
        result.setMessageCount(messageCount);
        result.setSuccessCount(successCount.get());
        result.setFailureCount(failureCount.get());
        result.setTotalBytes(totalBytes.get());
        result.setDurationMs(duration);
        result.setExactlyOnce(exactlyOnce);
        result.setDataIntegrityFailures(dataIntegrityFailures);

        // Calculate throughput
        double durationSec = Math.max(duration / 1000.0, 1);
        result.setThroughputMsgPerSec(successCount.get() / durationSec);

        // Calculate bandwidth
        double mbSent = totalBytes.get() / (1024.0 * 1024.0);
        result.setMbPerSec(mbSent / durationSec);

        // Calculate latency
        result.setAvgLatencyMs(calculateAverage(latencies));

        // Calculate percentiles
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        if (!sortedLatencies.isEmpty()) {
            result.setP50LatencyMs(percentile(sortedLatencies, 50));
            result.setP90LatencyMs(percentile(sortedLatencies, 90));
            result.setP99LatencyMs(percentile(sortedLatencies, 99));
        }

        return result;
    }

    private BenchmarkResult calculateConsumerResult(String testName, KafkaConnectConfig config,
                                                    int expectedCount, AtomicLong consumedCount,
                                                    AtomicLong totalBytes, long duration,
                                                    ConcurrentLinkedQueue<Long> latencies,
                                                    boolean exactlyOnce, long dataIntegrityFailures) {
        BenchmarkResult result = new BenchmarkResult();
        result.setTestName(testName);
        result.setConfiguration(buildConfigDescription(config, exactlyOnce));
        result.setMessageCount(expectedCount);
        result.setSuccessCount(consumedCount.get());
        result.setFailureCount(0);
        result.setTotalBytes(totalBytes.get());
        result.setDurationMs(duration);
        result.setExactlyOnce(exactlyOnce);
        result.setDataIntegrityFailures(dataIntegrityFailures);

        // Calculate throughput
        double durationSec = Math.max(duration / 1000.0, 1);
        result.setThroughputMsgPerSec(consumedCount.get() / durationSec);

        // Calculate bandwidth
        double mbReceived = totalBytes.get() / (1024.0 * 1024.0);
        result.setMbPerSec(mbReceived / durationSec);

        // Calculate latency
        result.setAvgLatencyMs(calculateAverage(latencies));

        // Calculate percentiles
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        if (!sortedLatencies.isEmpty()) {
            result.setP50LatencyMs(percentile(sortedLatencies, 50));
            result.setP90LatencyMs(percentile(sortedLatencies, 90));
            result.setP99LatencyMs(percentile(sortedLatencies, 99));
        }

        return result;
    }

    private String buildConfigDescription(KafkaConnectConfig config, boolean exactlyOnce) {
        StringBuilder sb = new StringBuilder();
        sb.append("acks=").append(config.getAcks());
        sb.append(", batch=").append(config.getBatchSize() / 1024).append("KB");
        sb.append(", linger=").append(config.getLingerMs()).append("ms");
        sb.append(", buffer=").append(config.getBufferMemory() / (1024 * 1024)).append("MB");
        if (config.getCompressionType() != null) {
            sb.append(", compression=").append(config.getCompressionType());
        }
        if (exactlyOnce) {
            sb.append(", EOS=[idempotent=").append(config.isEnableIdempotence());
            sb.append(", manualCommit=").append(config.isEnableManualCommit()).append("]");
        }
        return sb.toString();
    }

    private double calculateAverage(Collection<Long> values) {
        if (values.isEmpty()) return 0;
        long sum = 0;
        for (Long v : values) sum += v;
        return sum / (double) values.size();
    }

    private double percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private void printResult(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ").append(result.getTestName()).append(" Results ===\n");
        sb.append("Configuration: ").append(result.getConfiguration()).append("\n");
        sb.append(String.format("Messages: %d success, %d failed, %d total\n",
                result.getSuccessCount(), result.getFailureCount(), result.getMessageCount()));
        sb.append(String.format("Duration: %d ms\n", result.getDurationMs()));
        sb.append(String.format("Throughput: %.2f msg/sec\n", result.getThroughputMsgPerSec()));
        sb.append(String.format("Bandwidth: %.2f MB/sec\n", result.getMbPerSec()));
        sb.append(String.format("Avg Latency: %.2f ms\n", result.getAvgLatencyMs()));
        sb.append(String.format("P50 Latency: %.2f ms\n", result.getP50LatencyMs()));
        sb.append(String.format("P90 Latency: %.2f ms\n", result.getP90LatencyMs()));
        sb.append(String.format("P99 Latency: %.2f ms\n", result.getP99LatencyMs()));
        sb.append("Exactly-Once: ").append(result.isExactlyOnce() ? "YES" : "NO").append("\n");
        if (result.getDataIntegrityFailures() > 0) {
            sb.append("Data Integrity Failures: ").append(result.getDataIntegrityFailures()).append("\n");
        }

        // Print to both log and stdout
        System.out.println(sb.toString());
        log.info(sb.toString());
    }

    private void printComparisonTable() {
        log.info("\n=================================================================================================");
        log.info(String.format("%-25s | %-12s | %-12s | %-10s | %-10s | %-8s | %s",
                "Benchmark", "Throughput", "Bandwidth", "Duration", "Success", "EOS", "Configuration"));
        log.info("-------------------------------------------------------------------------------------------------");

        for (BenchmarkResult r : results) {
            log.info(String.format("%-25s | %-12.2f | %-12.2f | %-10d | %-10d | %-8s | %s",
                    r.getTestName(),
                    r.getThroughputMsgPerSec(),
                    r.getMbPerSec(),
                    r.getDurationMs(),
                    r.getSuccessCount(),
                    r.isExactlyOnce() ? "YES" : "NO",
                    r.getConfiguration()));
        }

        log.info("=================================================================================================");
    }
}
