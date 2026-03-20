package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure Kafka Client Benchmark - 不经过EventBus层
 *
 * 用于对比：
 * - kafka-demo: 100,000 msg/s
 * - EventBus RAW: 3,800 msg/s
 * - Pure Kafka Client: ???
 *
 * 如果Pure Kafka Client也很慢，说明是Kafka配置或测试环境问题
 * 如果Pure Kafka Client很快，说明是EventBus层的问题
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PureKafkaBenchmarkTest {

    private static final String TOPIC = "pure-kafka-benchmark";
    private static final int MESSAGE_COUNT = 100_000;
    private static final int MESSAGE_SIZE = 1024; // 1KB

    private KafkaContainer kafkaContainer;
    private Network network;
    private String bootstrapServers;

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
     * Pure Kafka Producer - 直接使用Kafka Client API
     */
    @Test
    @DisplayName("Pure Kafka Producer (no EventBus)")
    void testPureKafkaProducer() throws Exception {
        log.info("\n========== PURE KAFKA PRODUCER BENCHMARK ==========");

        // 使用与EventBus相同的配置
        Properties props = createKafkaDemoAlignedProps();

        System.out.println("Starting Pure Kafka Producer test (aligned with kafka-demo)...");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            AtomicLong successCount = new AtomicLong(0);
            AtomicLong failureCount = new AtomicLong(0);
            CountDownLatch latch = new CountDownLatch(MESSAGE_COUNT);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                final int messageId = i;
                byte[] payload = generatePayload(messageId, MESSAGE_SIZE);

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(messageId), payload);

                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                        System.err.println("Send failed: " + exception.getMessage());
                    }
                    latch.countDown();
                });

                // 与kafka-demo一致：每1000条flush一次
                if (i > 0 && i % 1000 == 0) {
                    producer.flush();
                }

                if (i > 0 && i % 10000 == 0) {
                    System.out.println("Sent " + i + " / " + MESSAGE_COUNT + " messages...");
                }
            }

            // 确保所有消息都发送出去
            producer.flush();

            // 等待所有消息完成
            boolean completed = latch.await(5, TimeUnit.MINUTES);
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            System.out.println("========== PURE KAFKA RESULTS (kafka-demo aligned) ==========");
            System.out.println("Messages: " + successCount.get() + " success, " + failureCount.get() + " failed, " + MESSAGE_COUNT + " total");
            System.out.println("Duration: " + duration + " ms");
            System.out.println("Throughput: " + String.format("%.2f", MESSAGE_COUNT * 1000.0 / duration) + " msg/sec");

            assertTrue(completed, "Should complete within timeout");
            assertEquals(MESSAGE_COUNT, successCount.get(), "All messages should succeed");
        }
    }

    /**
     * Pure Kafka Producer - 同步发送版本
     */
    @Test
    @DisplayName("Pure Kafka Producer - Sync (no EventBus)")
    void testPureKafkaProducerSync() throws Exception {
        log.info("\n========== PURE KAFKA PRODUCER SYNC BENCHMARK ==========");

        Properties props = createKafkaDemoAlignedProps();

        System.out.println("Starting Pure Kafka Producer SYNC test...");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            AtomicLong successCount = new AtomicLong(0);
            AtomicLong failureCount = new AtomicLong(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                final int messageId = i;
                byte[] payload = generatePayload(messageId, MESSAGE_SIZE);

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(messageId), payload);

                try {
                    producer.send(record).get(); // 同步等待
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("Send failed: " + e.getMessage());
                }

                if (i > 0 && i % 10000 == 0) {
                    System.out.println("Sent " + i + " / " + MESSAGE_COUNT + " messages...");
                }
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            System.out.println("========== PURE KAFKA SYNC RESULTS ==========");
            System.out.println("Messages: " + successCount.get() + " success, " + failureCount.get() + " failed, " + MESSAGE_COUNT + " total");
            System.out.println("Duration: " + duration + " ms");
            System.out.println("Throughput: " + String.format("%.2f", MESSAGE_COUNT * 1000.0 / duration) + " msg/sec");

            assertEquals(MESSAGE_COUNT, successCount.get(), "All messages should succeed");
        }
    }

    /**
     * Fire-and-forget version - 发送后不等待回调
     * 模拟kafka-demo可能使用的测试方法
     */
    @Test
    @DisplayName("Pure Kafka Producer - Fire and Forget")
    void testPureKafkaProducerFireAndForget() throws Exception {
        log.info("\n========== PURE KAFKA PRODUCER FIRE-AND-FORGET BENCHMARK ==========");

        Properties props = createKafkaDemoAlignedProps();

        System.out.println("Starting Pure Kafka Producer FIRE-AND-FORGET test...");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                byte[] payload = generatePayload(i, MESSAGE_SIZE);
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(i), payload);

                // Fire-and-forget: 不等待，不回调
                producer.send(record); // 忽略返回值

                if (i > 0 && i % 10000 == 0) {
                    System.out.println("Queued " + i + " / " + MESSAGE_COUNT + " messages...");
                }
            }

            // 确保所有消息都发送出去
            producer.flush();

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            System.out.println("========== FIRE-AND-FORGET RESULTS ==========");
            System.out.println("Messages: " + MESSAGE_COUNT + " queued");
            System.out.println("Duration: " + duration + " ms");
            System.out.println("Throughput: " + String.format("%.2f", MESSAGE_COUNT * 1000.0 / duration) + " msg/sec");
        }
    }

    /**
     * 创建与kafka-demo完全对齐的Kafka配置
     */
    private Properties createKafkaDemoAlignedProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("acks", "all");                    // 与kafka-demo一致
        props.put("retries", Integer.MAX_VALUE);    // 与kafka-demo一致
        props.put("batch.size", 65536);             // 64KB
        props.put("linger.ms", 10);                 // 10ms
        props.put("buffer.memory", 67108864);       // 64MB
        props.put("compression.type", "snappy");    // Snappy
        props.put("max.in.flight.requests.per.connection", 5);
        props.put("enable.idempotence", true);      // 与kafka-demo一致
        // 额外优化参数
        props.put("send.buffer.bytes", 1048576);   // 1MB send buffer
        props.put("receive.buffer.bytes", 1048576); // 1MB receive buffer
        props.put("socket.connection.setup.timeout.ms", 10000);
        props.put("socket.connection.timeout.ms", 10000);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        return props;
    }

    /**
     * 创建与kafka-demo完全对齐的配置，每1000条flush一次
     */
    private Properties createKafkaDemoAlignedPropsWithFlush() {
        Properties props = createKafkaDemoAlignedProps();
        // 与kafka-demo的sendBatch一致：每1000条flush
        return props;
    }

    private byte[] generatePayload(int messageId, int targetSize) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("MSG-%010d-", messageId));
        while (sb.length() < targetSize) {
            sb.append(String.format("%08d", messageId * 31 + sb.length()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
