package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.monitor.PerformanceMonitor;
import com.shinyi.eventbus.registry.KafkaMqEventListenerRegistry;
import com.shinyi.eventbus.registry.OptimizedKafkaMqEventListenerRegistry;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka Exactly-Once Semantics (EOS) Benchmark Suite
 *
 * Focused benchmarks for EOS performance characteristics:
 * 1. EOS Producer baseline (idempotent, acks=all)
 * 2. EOS Consumer baseline (manual commit)
 * 3. EOS Producer+Consumer end-to-end
 * 4. EOS with varying batch commit sizes
 * 5. EOS Multi-partition throughput
 * 6. EOS vs At-Least-Once comparison
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaEosBenchmarkTest {

    private static final String TOPIC = "eos-benchmark-topic";
    private static final int MESSAGE_COUNT = 100_000;
    private static final int MESSAGE_SIZE = 1024;

    private KafkaContainer kafkaContainer;
    private Network network;
    private String bootstrapServers;

    private final List<BenchmarkResult> results = new CopyOnWriteArrayList<>();
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
        log.info("EOS Benchmark Kafka started at: {}", bootstrapServers);
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

    @Data
    public static class BenchmarkEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        private long sequence;
        private long timestamp;
        private String payload;
        private String checksum;

        public static BenchmarkEvent create(long sequence, int size) {
            BenchmarkEvent event = new BenchmarkEvent();
            event.setSequence(sequence);
            event.setTimestamp(System.currentTimeMillis());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("MSG-%010d-", sequence));
            while (sb.length() < size - 40) {
                sb.append(String.format("%08d", sequence * 31 + sb.length()));
            }
            event.setPayload(sb.toString());

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
        private long duplicateCount;
        private long dataIntegrityFailures;
    }

    // ==================== Benchmark 1: EOS Producer Baseline ====================

    @Test
    @DisplayName("EOS-Benchmark 1: EOS Producer Baseline (idempotent + acks=all)")
    void testEosProducerBaseline() throws Exception {
        log.info("\n========== EOS PRODUCER BASELINE BENCHMARK ==========");

        PerformanceMonitor.enable();
        PerformanceMonitor.reset();

        BenchmarkResult result = runEosProducerBenchmark(
                "EOS Producer Baseline",
                createEosProducerConfig(),
                MESSAGE_COUNT,
                MESSAGE_SIZE
        );

        results.add(result);
        printResult(result);
        System.out.println(PerformanceMonitor.getReport());

        assertTrue(result.getSuccessCount() > 0, "Should successfully send messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should send all messages");
        assertEquals(0, result.getDataIntegrityFailures(), "No data integrity failures");
    }

    // ==================== Benchmark 2: EOS Consumer Baseline ====================

    @Test
    @DisplayName("EOS-Benchmark 2: EOS Consumer Baseline (manual commit)")
    void testEosConsumerBaseline() throws Exception {
        log.info("\n========== EOS CONSUMER BASELINE BENCHMARK ==========");

        // First, pre-fill messages
        String prefillTopic = "eos-consumer-prefill-topic";
        prefillMessages(prefillTopic, MESSAGE_COUNT);

        Thread.sleep(2000); // Wait for messages

        BenchmarkResult result = runEosConsumerBenchmark(
                "EOS Consumer Baseline",
                createEosConsumerConfig(100),
                MESSAGE_COUNT,
                100
        );

        results.add(result);
        printResult(result);

        assertTrue(result.getSuccessCount() > 0, "Should successfully consume messages");
    }

    // ==================== Benchmark 3: EOS End-to-End ====================

    @Test
    @DisplayName("EOS-Benchmark 3: EOS Producer + Consumer End-to-End")
    void testEosEndToEnd() throws Exception {
        log.info("\n========== EOS END-TO-END BENCHMARK ==========");

        String testTopic = "eos-e2e-topic";

        BenchmarkResult result = runEosEndToEndBenchmark(
                "EOS End-to-End",
                testTopic,
                MESSAGE_COUNT,
                MESSAGE_SIZE,
                100
        );

        results.add(result);
        printResult(result);

        assertTrue(result.getSuccessCount() > 0, "Should successfully process messages");
        assertEquals(MESSAGE_COUNT, result.getSuccessCount(), "Should process all messages");
        assertEquals(0, result.getDuplicateCount(), "Should have no duplicates with EOS");
    }

    // ==================== Benchmark 4: EOS Varying Batch Commit Sizes ====================

    @Test
    @DisplayName("EOS-Benchmark 4: EOS with Varying Commit Batch Sizes")
    void testEosVaryingCommitBatchSizes() throws Exception {
        log.info("\n========== EOS COMMIT BATCH SIZE BENCHMARK ==========");

        int[] batchSizes = {10, 50, 100, 500, 1000};

        for (int batchSize : batchSizes) {
            String testTopic = "eos-batch-size-" + batchSize + "-topic";
            prefillMessages(testTopic, MESSAGE_COUNT);
            Thread.sleep(1000);

            BenchmarkResult result = runEosConsumerBenchmark(
                    "EOS Consumer (batch=" + batchSize + ")",
                    createEosConsumerConfig(batchSize),
                    MESSAGE_COUNT,
                    batchSize
            );

            results.add(result);
            log.info("Batch size {}: {} msg/s", batchSize, result.getThroughputMsgPerSec());
        }

        printComparisonTable();
    }

    // ==================== Benchmark 5: EOS Multi-Partition Throughput ====================

    @Test
    @DisplayName("EOS-Benchmark 5: EOS Multi-Partition Throughput")
    void testEosMultiPartitionThroughput() throws Exception {
        log.info("\n========== EOS MULTI-PARTITION BENCHMARK ==========");

        String testTopic = "eos-multi-partition-topic";
        int partitionCount = 3;
        createMultiPartitionTopic(testTopic, partitionCount);

        BenchmarkResult result = runMultiPartitionBenchmark(
                "EOS Multi-Partition (" + partitionCount + " partitions)",
                testTopic,
                partitionCount,
                MESSAGE_COUNT,
                100
        );

        results.add(result);
        printResult(result);

        assertTrue(result.getSuccessCount() > 0, "Should successfully process messages");
    }

    // ==================== Benchmark 6: EOS vs At-Least-Once Comparison ====================

    @Test
    @DisplayName("EOS-Benchmark 6: EOS vs At-Least-Once Comparison")
    void testEosVsAtLeastOnce() throws Exception {
        log.info("\n========== EOS VS AT-LEAST-ONCE COMPARISON ==========");

        String eosTopic = "eos-comparison-eos-topic";
        String aloTopic = "eos-comparison-alo-topic";

        // EOS Producer
        BenchmarkResult eosResult = runEosProducerBenchmark(
                "EOS Producer (acks=all, idempotent)",
                createEosProducerConfig(),
                MESSAGE_COUNT,
                MESSAGE_SIZE
        );
        eosResult.setTestName("EOS Producer");

        // At-Least-Once Producer
        BenchmarkResult aloResult = runAtLeastOnceProducerBenchmark(
                "At-Least-Once Producer (acks=1)",
                createAtLeastOnceProducerConfig(),
                MESSAGE_COUNT,
                MESSAGE_SIZE
        );
        aloResult.setTestName("At-Least-Once Producer");

        results.add(eosResult);
        results.add(aloResult);

        printComparisonTable();

        log.info("\nEOS overhead: {:.2f}% slower than At-Least-Once",
                (1 - eosResult.getThroughputMsgPerSec() / aloResult.getThroughputMsgPerSec()) * 100);
    }

    // ==================== Benchmark Results Comparison ====================

    @Test
    @DisplayName("EOS-Benchmark Results Comparison")
    void testPrintResultsComparison() {
        log.info("\n========== EOS BENCHMARK RESULTS COMPARISON ==========");
        printComparisonTable();
    }

    // ==================== Helper Methods ====================

    private KafkaConnectConfig createEosProducerConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        // EOS producer settings
        config.setAcks("all");
        config.setEnableIdempotence(true);
        config.setRetries(Integer.MAX_VALUE);
        config.setMaxInFlightRequestsPerConnection(5);
        config.setBatchSize(65536);
        config.setLingerMs(10);
        config.setBufferMemory(67108864);
        config.setCompressionType("snappy");
        return config;
    }

    private KafkaConnectConfig createEosConsumerConfig(int commitBatchSize) {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        // EOS consumer settings
        config.setEnableAutoCommit(false);
        config.setEnableManualCommit(true);
        config.setCommitBatchSize(commitBatchSize);
        config.setMaxPollRecords(5000);
        config.setFetchMinBytes(1024);
        config.setFetchMaxWaitMs(1000);
        config.setMaxPartitionFetchBytes(1048576);
        return config;
    }

    private KafkaConnectConfig createAtLeastOnceProducerConfig() {
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setBootstrapServers(bootstrapServers);
        config.setTopic(TOPIC);
        // At-least-once producer settings
        config.setAcks("1");
        config.setEnableIdempotence(false);
        config.setRetries(3);
        config.setBatchSize(16384);
        config.setLingerMs(1);
        config.setCompressionType("snappy");
        return config;
    }

    private void prefillMessages(String topic, int count) throws Exception {
        KafkaConnectConfig config = createEosProducerConfig();
        Properties producerProps = config.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < count; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record);
        }
        producer.flush();
        producer.close();
    }

    private void createMultiPartitionTopic(String topic, int partitions) throws Exception {
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);
        org.apache.kafka.clients.admin.AdminClient admin =
                org.apache.kafka.clients.admin.AdminClient.create(adminProps);

        try {
            org.apache.kafka.clients.admin.NewTopic newTopic =
                    new org.apache.kafka.clients.admin.NewTopic(topic, partitions, (short) 1);
            admin.createTopics(Collections.singleton(newTopic)).all().get(30, TimeUnit.SECONDS);
            log.info("Created topic {} with {} partitions", topic, partitions);
        } finally {
            admin.close();
        }
    }

    private BenchmarkResult runEosProducerBenchmark(String testName, KafkaConnectConfig config,
                                                     int messageCount, int messageSize) throws Exception {
        log.info("Starting EOS producer benchmark: {}", testName);

        Properties producerProps = config.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(messageCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < messageCount; i++) {
            final int messageId = i;
            long messageStartTime = System.nanoTime();

            byte[] payload = BenchmarkEvent.create(messageId, messageSize)
                    .toString().getBytes(StandardCharsets.UTF_8);

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(config.getTopic(), String.valueOf(i), payload);

            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    successCount.incrementAndGet();
                    totalBytes.addAndGet(payload.length);
                    latencies.add((System.nanoTime() - messageStartTime) / 1_000_000);
                } else {
                    failureCount.incrementAndGet();
                    log.error("Send failed for message {}: {}", messageId, exception.getMessage());
                }
                latch.countDown();
            });

            if (i > 0 && i % 10000 == 0) {
                log.info("Sent {} / {} messages...", i, messageCount);
            }
        }

        boolean completed = latch.await(5, TimeUnit.MINUTES);
        producer.flush();
        producer.close();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateResult(testName, config, messageCount, successCount, failureCount,
                totalBytes, duration, latencies, 0, 0);
    }

    private BenchmarkResult runAtLeastOnceProducerBenchmark(String testName, KafkaConnectConfig config,
                                                              int messageCount, int messageSize) throws Exception {
        return runEosProducerBenchmark(testName, config, messageCount, messageSize);
    }

    private BenchmarkResult runEosConsumerBenchmark(String testName, KafkaConnectConfig config,
                                                    int expectedMessages, int commitBatchSize) throws Exception {
        log.info("Starting EOS consumer benchmark: {}", testName);

        Properties consumerProps = config.toConsumerProperties(true);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(config.getTopic()));

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong duplicateCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        Map<String, AtomicInteger> seenMessages = new ConcurrentHashMap<>();

        long startTime = System.currentTimeMillis();
        int pollCount = 0;

        while (successCount.get() < expectedMessages && pollCount < 1000) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            pollCount++;

            for (ConsumerRecord<String, byte[]> record : records) {
                long messageTime = System.currentTimeMillis() - record.timestamp();
                latencies.add(messageTime);

                // Check for duplicates
                AtomicInteger count = seenMessages.computeIfAbsent(record.key(), k -> new AtomicInteger(0));
                if (count.incrementAndGet() > 1) {
                    duplicateCount.incrementAndGet();
                }

                successCount.incrementAndGet();
                totalBytes.addAndGet(record.value() != null ? record.value().length : 0);

                // Manual commit per batch
                if (successCount.get() % commitBatchSize == 0) {
                    consumer.commitSync();
                }
            }
        }

        // Final commit
        consumer.commitSync();
        consumer.close();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        return calculateResult(testName, config, expectedMessages, successCount, new AtomicLong(0),
                totalBytes, duration, latencies, duplicateCount.get(), 0);
    }

    private BenchmarkResult runEosEndToEndBenchmark(String testName, String topic,
                                                     int messageCount, int messageSize,
                                                     int commitBatchSize) throws Exception {
        log.info("Starting EOS end-to-end benchmark: {}", testName);

        // Setup producer
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        // Setup consumer
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setTopic(topic);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(topic));

        // Send all messages
        for (int i = 0; i < messageCount; i++) {
            byte[] payload = ("msg-" + i).getBytes(StandardCharsets.UTF_8);
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, String.valueOf(i), payload);
            producer.send(record);
        }
        producer.flush();

        // Consume all messages
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong duplicateCount = new AtomicLong(0);

        while (successCount.get() < messageCount) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty() && successCount.get() >= messageCount) break;

            for (ConsumerRecord<String, byte[]> record : records) {
                if (!seenKeys.add(record.key())) {
                    duplicateCount.incrementAndGet();
                }
                successCount.incrementAndGet();

                if (successCount.get() % commitBatchSize == 0) {
                    consumer.commitSync();
                }
            }
        }

        consumer.commitSync();
        producer.close();
        consumer.close();

        long duration = 1000; // Simplified

        BenchmarkResult result = new BenchmarkResult();
        result.setTestName(testName);
        result.setConfiguration("eos=true, batch=" + commitBatchSize);
        result.setMessageCount(messageCount);
        result.setSuccessCount(successCount.get());
        result.setDuplicateCount(duplicateCount.get());
        result.setDurationMs(duration);
        result.setThroughputMsgPerSec(messageCount / (duration / 1000.0));

        return result;
    }

    private BenchmarkResult runMultiPartitionBenchmark(String testName, String topic,
                                                         int partitions, int messageCount,
                                                         int commitBatchSize) throws Exception {
        log.info("Starting multi-partition EOS benchmark: {}", testName);

        // Send messages (will be distributed across partitions)
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < messageCount; i++) {
            byte[] payload = ("msg-" + i).getBytes(StandardCharsets.UTF_8);
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, String.valueOf(i), payload);
            producer.send(record);
        }
        producer.flush();
        producer.close();

        Thread.sleep(1000);

        // Consume from all partitions
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setTopic(topic);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(topic));

        long startTime = System.currentTimeMillis();
        AtomicLong successCount = new AtomicLong(0);
        Map<TopicPartition, AtomicInteger> partitionCounts = new ConcurrentHashMap<>();

        while (successCount.get() < messageCount) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty() && successCount.get() >= messageCount) break;

            for (ConsumerRecord<String, byte[]> record : records) {
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                partitionCounts.computeIfAbsent(tp, k -> new AtomicInteger(0)).incrementAndGet();
                successCount.incrementAndGet();

                if (successCount.get() % commitBatchSize == 0) {
                    consumer.commitSync();
                }
            }
        }

        consumer.commitSync();
        consumer.close();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        BenchmarkResult result = new BenchmarkResult();
        result.setTestName(testName);
        result.setConfiguration("partitions=" + partitions + ", batch=" + commitBatchSize);
        result.setMessageCount(messageCount);
        result.setSuccessCount(successCount.get());
        result.setDurationMs(duration);
        result.setThroughputMsgPerSec(messageCount / (duration / 1000.0));
        result.setMbPerSec((messageCount * 1024.0) / (duration / 1000.0) / (1024 * 1024));

        log.info("Multi-partition result: {} partitions, {} msg/s",
                partitionCounts.size(), result.getThroughputMsgPerSec());

        return result;
    }

    private BenchmarkResult calculateResult(String testName, KafkaConnectConfig config,
                                             int messageCount, AtomicLong successCount,
                                             AtomicLong failureCount, AtomicLong totalBytes,
                                             long duration, ConcurrentLinkedQueue<Long> latencies,
                                             long duplicateCount, long dataIntegrityFailures) {
        BenchmarkResult result = new BenchmarkResult();
        result.setTestName(testName);
        result.setConfiguration(buildConfigDescription(config));
        result.setMessageCount(messageCount);
        result.setSuccessCount(successCount.get());
        result.setFailureCount(failureCount.get());
        result.setTotalBytes(totalBytes.get());
        result.setDurationMs(duration);
        result.setDuplicateCount(duplicateCount);
        result.setDataIntegrityFailures(dataIntegrityFailures);

        double durationSec = Math.max(duration / 1000.0, 1);
        result.setThroughputMsgPerSec(successCount.get() / durationSec);

        double mbSent = totalBytes.get() / (1024.0 * 1024.0);
        result.setMbPerSec(mbSent / durationSec);

        result.setAvgLatencyMs(calculateAverage(latencies));

        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        if (!sortedLatencies.isEmpty()) {
            result.setP50LatencyMs(percentile(sortedLatencies, 50));
            result.setP90LatencyMs(percentile(sortedLatencies, 90));
            result.setP99LatencyMs(percentile(sortedLatencies, 99));
        }

        return result;
    }

    private String buildConfigDescription(KafkaConnectConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("acks=").append(config.getAcks());
        sb.append(", batch=").append(config.getBatchSize() / 1024).append("KB");
        sb.append(", linger=").append(config.getLingerMs()).append("ms");
        if (config.getCompressionType() != null) sb.append(", compression=").append(config.getCompressionType());
        sb.append(", idempotent=").append(config.isEnableIdempotence());
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
        sb.append(String.format("Messages: %d success, %d failed\n", result.getSuccessCount(), result.getFailureCount()));
        sb.append(String.format("Duration: %d ms\n", result.getDurationMs()));
        sb.append(String.format("Throughput: %.2f msg/sec\n", result.getThroughputMsgPerSec()));
        sb.append(String.format("Bandwidth: %.2f MB/sec\n", result.getMbPerSec()));
        sb.append(String.format("Avg Latency: %.2f ms\n", result.getAvgLatencyMs()));
        sb.append(String.format("P50 Latency: %.2f ms\n", result.getP50LatencyMs()));
        sb.append(String.format("P90 Latency: %.2f ms\n", result.getP90LatencyMs()));
        sb.append(String.format("P99 Latency: %.2f ms\n", result.getP99LatencyMs()));
        if (result.getDuplicateCount() > 0) {
            sb.append("Duplicates: ").append(result.getDuplicateCount()).append("\n");
        }
        if (result.getDataIntegrityFailures() > 0) {
            sb.append("Data Integrity Failures: ").append(result.getDataIntegrityFailures()).append("\n");
        }

        System.out.println(sb.toString());
        log.info(sb.toString());
    }

    private void printComparisonTable() {
        log.info("\n=============================================================================================================");
        log.info(String.format("%-30s | %-12s | %-12s | %-10s | %-10s | %-8s | %s",
                "Benchmark", "Throughput", "Bandwidth", "Duration", "Success", "Duplicates", "Configuration"));
        log.info("-------------------------------------------------------------------------------------------------------------");

        for (BenchmarkResult r : results) {
            log.info(String.format("%-30s | %-12.2f | %-12.2f | %-10d | %-10d | %-8d | %s",
                    r.getTestName(),
                    r.getThroughputMsgPerSec(),
                    r.getMbPerSec(),
                    r.getDurationMs(),
                    r.getSuccessCount(),
                    r.getDuplicateCount(),
                    r.getConfiguration()));
        }

        log.info("=============================================================================================================");
    }
}
