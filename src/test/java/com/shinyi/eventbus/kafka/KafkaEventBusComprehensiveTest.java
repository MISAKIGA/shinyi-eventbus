package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.registry.OptimizedKafkaMqEventListenerRegistry;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.support.EventListenerRegistryManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka EventBus Comprehensive Performance Test
 *
 * Tests all combinations of:
 * - Producer: Batch/Non-Batch, Async/Sync, Flush/No-Flush, EOS/Non-EOS
 * - Consumer: Auto-Commit/Manual-Commit, EOS/Non-EOS
 * - End-to-End: Full pipeline comparison
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaEventBusComprehensiveTest {

    private static final String TOPIC = "comprehensive-test-topic";
    private static final int MESSAGE_COUNT = 10_000;
    private static final int MESSAGE_SIZE = 1024;

    private KafkaContainer kafkaContainer;
    private Network network;
    private String bootstrapServers;

    private final List<TestResult> allResults = new CopyOnWriteArrayList<>();

    @BeforeAll
    void startKafka() {
        network = Network.newNetwork();
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withExposedPorts(9092, 9093);

        Startables.deepStart(kafkaContainer).join();
        bootstrapServers = kafkaContainer.getBootstrapServers();
        System.out.println("Kafka started at: " + bootstrapServers);
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

    // ==================== PRODUCER TESTS ====================

    /**
     * Test 1: Async Producer with internal batching (recommended)
     */
    @Test
    @DisplayName("Producer: Async + Batch (autoFlush=false)")
    void testProducerAsyncBatch() throws Exception {
        System.out.println("\n========== PRODUCER: ASYNC + BATCH ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setAutoFlush(false);
        config.setFlushInterval(Integer.MAX_VALUE);

        TestResult result = runProducerTest("Async+Batch", config, false, true);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 2: Sync Producer with immediate flush
     */
    @Test
    @DisplayName("Producer: Sync + No Batch (autoFlush=true)")
    void testProducerSyncNoBatch() throws Exception {
        System.out.println("\n========== PRODUCER: SYNC + NO BATCH ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(16384);
        config.setLingerMs(0);
        config.setAutoFlush(true);
        config.setFlushInterval(1); // Flush every message

        TestResult result = runProducerTest("Sync+NoBatch", config, false, false);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 3: Periodic flush (kafka-demo style)
     */
    @Test
    @DisplayName("Producer: Async + Periodic Flush (flushInterval=1000)")
    void testProducerPeriodicFlush() throws Exception {
        System.out.println("\n========== PRODUCER: ASYNC + PERIODIC FLUSH ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setAutoFlush(true);
        config.setFlushInterval(1000);

        TestResult result = runProducerTest("PeriodicFlush", config, false, true);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 4: EOS Producer (idempotent)
     */
    @Test
    @DisplayName("Producer: EOS (idempotent=true)")
    void testProducerEos() throws Exception {
        System.out.println("\n========== PRODUCER: EOS (IDEMPOTENT) ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setEnableIdempotence(true);
        config.setAutoFlush(false);
        config.setFlushInterval(Integer.MAX_VALUE);

        TestResult result = runProducerTest("EOS-Producer", config, true, true);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 5: High throughput config (compression + large batch)
     */
    @Test
    @DisplayName("Producer: High Throughput (compression=snappy, large batch)")
    void testProducerHighThroughput() throws Exception {
        System.out.println("\n========== PRODUCER: HIGH THROUGHPUT ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(131072);  // 128KB
        config.setLingerMs(20);
        config.setCompressionType("snappy");
        config.setBufferMemory(134217728); // 128MB
        config.setAutoFlush(false);
        config.setFlushInterval(Integer.MAX_VALUE);

        TestResult result = runProducerTest("HighThroughput", config, false, true);
        allResults.add(result);
        printResult(result);
    }

    // ==================== CONSUMER TESTS ====================

    /**
     * Test 6: Auto-commit Consumer
     */
    @Test
    @DisplayName("Consumer: Auto-Commit")
    void testConsumerAutoCommit() throws Exception {
        System.out.println("\n========== CONSUMER: AUTO-COMMIT ==========");

        // First produce messages
        KafkaConnectConfig prodConfig = createBaseConfig();
        prodConfig.setAutoFlush(false);
        produceMessagesDirect(prodConfig, 10000);

        // Then consume with auto-commit
        KafkaConnectConfig consConfig = createBaseConfig();
        consConfig.setEnableAutoCommit(true);
        consConfig.setMaxPollRecords(500);

        TestResult result = runConsumerTest("AutoCommit-Consumer", consConfig, 10000, false);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 7: Manual commit Consumer (EOS style)
     */
    @Test
    @DisplayName("Consumer: Manual Commit (commitBatchSize=100)")
    void testConsumerManualCommit() throws Exception {
        System.out.println("\n========== CONSUMER: MANUAL COMMIT ==========");

        // First produce messages
        KafkaConnectConfig prodConfig = createBaseConfig();
        prodConfig.setAutoFlush(false);
        produceMessagesDirect(prodConfig, 10000);

        // Then consume with manual commit
        KafkaConnectConfig consConfig = createBaseConfig();
        consConfig.setEnableAutoCommit(false);
        consConfig.setEnableManualCommit(true);
        consConfig.setCommitBatchSize(100);
        consConfig.setMaxPollRecords(500);

        TestResult result = runConsumerTest("ManualCommit-Consumer", consConfig, 10000, false);
        allResults.add(result);
        printResult(result);
    }

    /**
     * Test 8: High throughput Consumer
     */
    @Test
    @DisplayName("Consumer: High Throughput (large fetch)")
    void testConsumerHighThroughput() throws Exception {
        System.out.println("\n========== CONSUMER: HIGH THROUGHPUT ==========");

        // First produce messages
        KafkaConnectConfig prodConfig = createBaseConfig();
        prodConfig.setAutoFlush(false);
        produceMessagesDirect(prodConfig, 50000);

        // Then consume with optimized fetch
        KafkaConnectConfig consConfig = createBaseConfig();
        consConfig.setEnableAutoCommit(true);
        consConfig.setMaxPollRecords(5000);
        consConfig.setFetchMinBytes(1024);
        consConfig.setFetchMaxWaitMs(500);
        consConfig.setMaxPartitionFetchBytes(10485760); // 10MB

        TestResult result = runConsumerTest("HighThroughput-Consumer", consConfig, 50000, false);
        allResults.add(result);
        printResult(result);
    }

    // ==================== END-TO-END TESTS ====================

    /**
     * Test 9: End-to-End Non-EOS Pipeline
     */
    @Test
    @DisplayName("End-to-End: Non-EOS Pipeline")
    void testEndToEndNonEos() throws Exception {
        System.out.println("\n========== E2E: NON-EOS PIPELINE ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setAutoFlush(false);
        config.setEnableIdempotence(false);
        config.setEnableAutoCommit(true);

        TestResult prodResult = runProducerTest("E2E-NonEOS-Producer", config, false, true);
        TestResult consResult = runConsumerTest("E2E-NonEOS-Consumer", config, MESSAGE_COUNT, false);

        allResults.add(prodResult);
        allResults.add(consResult);

        printResult(prodResult);
        printResult(consResult);

        // Validate
        assertEquals(MESSAGE_COUNT, prodResult.successCount);
        assertTrue(consResult.successCount >= MESSAGE_COUNT * 0.95, "Should consume at least 95% of messages");
    }

    /**
     * Test 10: End-to-End EOS Pipeline
     */
    @Test
    @DisplayName("End-to-End: EOS Pipeline (idempotent + manual commit)")
    void testEndToEndEos() throws Exception {
        System.out.println("\n========== E2E: EOS PIPELINE ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setAutoFlush(false);
        config.setEnableIdempotence(true);
        config.setEnableAutoCommit(false);
        config.setEnableManualCommit(true);
        config.setCommitBatchSize(100);

        TestResult prodResult = runProducerTest("E2E-EOS-Producer", config, true, true);
        TestResult consResult = runConsumerTest("E2E-EOS-Consumer", config, MESSAGE_COUNT, true);

        allResults.add(prodResult);
        allResults.add(consResult);

        printResult(prodResult);
        printResult(consResult);

        // Validate
        assertEquals(MESSAGE_COUNT, prodResult.successCount);
        assertTrue(consResult.duplicateCount == 0, "EOS should have no duplicates");
        assertTrue(consResult.successCount >= MESSAGE_COUNT * 0.95, "Should consume at least 95% of messages");
    }

    /**
     * Test 11: Multi-threaded Producer
     */
    @Test
    @DisplayName("Producer: Multi-threaded (4 threads)")
    void testMultiThreadedProducer() throws Exception {
        System.out.println("\n========== PRODUCER: MULTI-THREADED (4 threads) ==========");

        KafkaConnectConfig config = createBaseConfig();
        config.setAcks("all");
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setAutoFlush(false);
        config.setFlushInterval(Integer.MAX_VALUE);

        TestResult result = runMultiThreadedProducerTest("MultiThreaded-Producer", config, 4, MESSAGE_COUNT);
        allResults.add(result);
        printResult(result);
    }

    // ==================== SUMMARY ====================

    @AfterAll
    void printSummary() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         COMPREHENSIVE TEST SUMMARY                                ║");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════════╣");

        // Sort by throughput
        allResults.sort((a, b) -> Double.compare(b.throughputMsgPerSec, a.throughputMsgPerSec));

        for (TestResult r : allResults) {
            System.out.printf("║ %-35s │ %10.2f msg/s │ %6.2f MB/s │ %-5s │ %-8s ║%n",
                    r.testName,
                    r.throughputMsgPerSec,
                    r.mbPerSec,
                    r.async ? "ASYNC" : "SYNC",
                    r.eos ? "EOS" : "NON-EOS");
        }

        System.out.println("╚════════════════════════════════════════════════════════════════════════════════════╝");

        // Find best configurations
        Optional<TestResult> bestProducer = allResults.stream()
                .filter(r -> r.testName.contains("Producer"))
                .max(Comparator.comparingDouble(r -> r.throughputMsgPerSec));

        Optional<TestResult> bestConsumer = allResults.stream()
                .filter(r -> r.testName.contains("Consumer"))
                .max(Comparator.comparingDouble(r -> r.throughputMsgPerSec));

        System.out.println();
        System.out.println("BEST CONFIGURATIONS:");
        bestProducer.ifPresent(r -> System.out.printf("  Producer: %s with %.2f msg/s%n", r.testName, r.throughputMsgPerSec));
        bestConsumer.ifPresent(r -> System.out.printf("  Consumer: %s with %.2f msg/s%n", r.testName, r.throughputMsgPerSec));

        // EOS overhead
        TestResult nonEos = allResults.stream()
                .filter(r -> r.testName.contains("E2E-NonEOS"))
                .findFirst()
                .orElse(null);
        TestResult eos = allResults.stream()
                .filter(r -> r.testName.contains("E2E-EOS"))
                .findFirst()
                .orElse(null);

        if (nonEos != null && eos != null) {
            double overhead = (eos.throughputMsgPerSec / nonEos.throughputMsgPerSec - 1) * 100;
            System.out.println();
            System.out.printf("EOS OVERHEAD: %.2f%%%n", overhead);
            System.out.printf("  Non-EOS: %.2f msg/s%n", nonEos.throughputMsgPerSec);
            System.out.printf("  EOS: %.2f msg/s%n", eos.throughputMsgPerSec);
        }
    }

    // ==================== HELPER METHODS ====================

    private KafkaConnectConfig createBaseConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        config.setGroupId("test-group-" + System.currentTimeMillis());
        config.setAcks("1");
        config.setRetries(3);
        config.setBatchSize(16384);
        config.setLingerMs(1);
        config.setBufferMemory(33554432);
        config.setCompressionType("none");
        config.setMaxPollRecords(500);
        return config;
    }

    private TestResult runProducerTest(String testName, KafkaConnectConfig config,
                                        boolean eos, boolean async) throws Exception {
        System.out.println("Starting producer test: " + testName);

        EventListenerRegistryManager registryManager = createRegistryManager(config);
        registryManager.start();

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(MESSAGE_COUNT);

        long startTime = System.currentTimeMillis();

        try {
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                final int messageId = i;
                BenchmarkEvent event = BenchmarkEvent.create(messageId, MESSAGE_SIZE);

                EventModel<BenchmarkEvent> eventModel = EventModel.build(
                        TOPIC,
                        event,
                        String.valueOf(messageId),
                        async,  // async flag
                        "JSON",
                        new EventCallback() {
                            @Override
                            public void onSuccess(EventResult eventResult) {
                                successCount.incrementAndGet();
                                totalBytes.addAndGet(estimateSize(event));
                                latch.countDown();
                            }

                            @Override
                            public void onFailure(EventResult eventResult, Throwable e) {
                                failureCount.incrementAndGet();
                                log.error("Send failed: {}", e.getMessage());
                                latch.countDown();
                            }
                        }
                );

                registryManager.publish(EventBusType.KAFKA, eventModel);
            }

            latch.await(5, TimeUnit.MINUTES);
        } finally {
            registryManager.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return createResult(testName, config, MESSAGE_COUNT, successCount.get(),
                failureCount.get(), totalBytes.get(), duration, async, eos);
    }

    private TestResult runMultiThreadedProducerTest(String testName, KafkaConnectConfig config,
                                                     int threadCount, int messagesPerThread) throws Exception {
        System.out.println("Starting multi-threaded producer test: " + testName + " with " + threadCount + " threads");

        EventListenerRegistryManager registryManager = createRegistryManager(config);
        registryManager.start();

        int totalMessages = messagesPerThread * threadCount;
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(totalMessages);

        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    for (int i = 0; i < messagesPerThread; i++) {
                        final int messageId = threadId * messagesPerThread + i;
                        BenchmarkEvent event = BenchmarkEvent.create(messageId, MESSAGE_SIZE);

                        EventModel<BenchmarkEvent> eventModel = EventModel.build(
                                TOPIC,
                                event,
                                String.valueOf(messageId),
                                true,  // async
                                "JSON",
                                new EventCallback() {
                                    @Override
                                    public void onSuccess(EventResult eventResult) {
                                        successCount.incrementAndGet();
                                        totalBytes.addAndGet(estimateSize(event));
                                        latch.countDown();
                                    }

                                    @Override
                                    public void onFailure(EventResult eventResult, Throwable e) {
                                        failureCount.incrementAndGet();
                                        latch.countDown();
                                    }
                                }
                        );

                        registryManager.publish(EventBusType.KAFKA, eventModel);
                    }
                });
            }

            latch.await(5, TimeUnit.MINUTES);
        } finally {
            executor.shutdown();
            registryManager.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return createResult(testName, config, totalMessages, successCount.get(),
                failureCount.get(), totalBytes.get(), duration, true, false);
    }

    private TestResult runConsumerTest(String testName, KafkaConnectConfig config,
                                        int expectedMessages, boolean checkDuplicates) throws Exception {
        System.out.println("Starting consumer test: " + testName);

        Properties consumerProps = config.toConsumerProperties();
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC));

        AtomicLong consumedCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong duplicateCount = new AtomicLong(0);
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();

        long startTime = System.currentTimeMillis();
        int maxPolls = 100;
        int pollCount = 0;

        try {
            while (consumedCount.get() < expectedMessages && pollCount < maxPolls) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, byte[]> record : records) {
                    consumedCount.incrementAndGet();
                    totalBytes.addAndGet(record.value() != null ? record.value().length : 0);

                    if (checkDuplicates) {
                        if (!seenKeys.add(record.key())) {
                            duplicateCount.incrementAndGet();
                        }
                    }
                }
                pollCount++;
            }
        } finally {
            consumer.close();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        TestResult result = createResult(testName, config, expectedMessages,
                consumedCount.get(), 0, totalBytes.get(), duration, true, config.isEnableIdempotence());
        result.duplicateCount = duplicateCount.get();
        return result;
    }

    private void produceMessagesDirect(KafkaConnectConfig config, int count) {
        Properties producerProps = config.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        try {
            for (int i = 0; i < count; i++) {
                String key = String.valueOf(i);
                String value = "Message-" + i + "-" + new String(new byte[MESSAGE_SIZE], StandardCharsets.UTF_8);
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, key, value.getBytes());
                producer.send(record).get();
            }
            producer.flush();
        } catch (Exception e) {
            log.error("Failed to produce messages: {}", e.getMessage());
        } finally {
            producer.close();
        }
    }

    private EventListenerRegistryManager createRegistryManager(KafkaConnectConfig config) {
        org.springframework.context.support.GenericApplicationContext ctx =
                new org.springframework.context.support.GenericApplicationContext();

        OptimizedKafkaMqEventListenerRegistry<EventModel<?>> registry =
                new OptimizedKafkaMqEventListenerRegistry<>(ctx, "test-kafka", config);
        registry.init();

        ctx.registerBean("kafkaEventListenerRegistry", EventListenerRegistry.class, () -> registry);
        ctx.registerBean(EventListenerRegistryManager.class);
        ctx.refresh();

        return ctx.getBean(EventListenerRegistryManager.class);
    }

    private TestResult createResult(String testName, KafkaConnectConfig config,
                                    int messageCount, long successCount, long failureCount,
                                    long totalBytes, long durationMs, boolean async, boolean eos) {
        TestResult result = new TestResult();
        result.testName = testName;
        result.configuration = config.toString();
        result.messageCount = messageCount;
        result.successCount = successCount;
        result.failureCount = failureCount;
        result.totalBytes = totalBytes;
        result.durationMs = durationMs;
        result.throughputMsgPerSec = (durationMs > 0) ? (successCount * 1000.0 / durationMs) : 0;
        result.mbPerSec = (durationMs > 0) ? (totalBytes * 1000.0 / durationMs / 1024 / 1024) : 0;
        result.async = async;
        result.eos = eos;
        return result;
    }

    private int estimateSize(BenchmarkEvent event) {
        return MESSAGE_SIZE; // Approximate
    }

    private void printResult(TestResult result) {
        System.out.println("━━━ " + result.testName + " ━━━");
        System.out.printf("  Throughput: %.2f msg/s (%.2f MB/s)%n", result.throughputMsgPerSec, result.mbPerSec);
        System.out.println("  Duration: " + result.durationMs + " ms");
        System.out.printf("  Success: %d / %d (%d failures)%n",
                result.successCount, result.messageCount, result.failureCount);
        if (result.duplicateCount > 0) {
            System.out.println("  Duplicates: " + result.duplicateCount);
        }
        System.out.println("  Mode: " + (result.async ? "ASYNC" : "SYNC") + " | " + (result.eos ? "EOS" : "NON-EOS"));
    }

    @Data
    public static class TestResult {
        String testName;
        String configuration;
        int messageCount;
        long successCount;
        long failureCount;
        long totalBytes;
        long durationMs;
        double throughputMsgPerSec;
        double mbPerSec;
        boolean async;
        boolean eos;
        long duplicateCount;
    }

    @Data
    public static class BenchmarkEvent implements Serializable {
        private int id;
        private byte[] data;
        private long timestamp;
        private String checksum;

        public static BenchmarkEvent create(int id, int size) {
            BenchmarkEvent event = new BenchmarkEvent();
            event.id = id;
            event.data = new byte[size];
            event.timestamp = System.currentTimeMillis();
            event.checksum = "checksum-" + id;
            return event;
        }

        public boolean validateIntegrity() {
            return checksum != null && checksum.equals("checksum-" + id);
        }
    }
}
