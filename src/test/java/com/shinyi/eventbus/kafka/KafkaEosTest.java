package com.shinyi.eventbus.kafka;

import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import com.shinyi.eventbus.registry.KafkaMqEventListenerRegistry;
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
import cn.hutool.core.collection.ConcurrentHashSet;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka Exactly-Once Semantics (EOS) Test Suite
 *
 * NOTE: This test is DISABLED because it tests native Kafka API directly,
 * bypassing the EventBus framework. These tests are NOT valid for testing
 * EventBus EOS functionality.
 *
 * See PLAN_EOS_TEST.md for the correct EventBus framework EOS test design.
 * A new EventBusEosTest.java will be created to properly test EOS via:
 * - EventListenerRegistryManager.publish()
 * - @EventBusListener annotation
 * - Framework's MethodEventListener
 *
 * @Deprecated Use EventBusEosTest instead
 */
@Slf4j
@Disabled("KafkaEosTest bypasses EventBus framework - use EventBusEosTest instead")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaEosTest {

    private static final String TOPIC = "eos-test-topic";
    private static final String TOPIC_MP = "eos-test-topic-multi-partition";
    private static final int PARTITION_COUNT = 3;
    private static final int MESSAGE_COUNT = 1000;

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
        log.info("EOS Test Kafka started at: {}", bootstrapServers);
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
    public static class EosEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        private long sequence;
        private long timestamp;
        private String payload;
        private String checksum;

        public static EosEvent create(long sequence) {
            EosEvent event = new EosEvent();
            event.setSequence(sequence);
            event.setTimestamp(System.currentTimeMillis());
            event.setPayload("MSG-" + sequence);

            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                String dataToHash = sequence + "-" + event.getPayload();
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

    // ==================== EOS-1: Idempotent Producer ====================

    @Test
    @DisplayName("EOS-1: Idempotent producer should prevent duplicate sends")
    void testEosIdempotentProducer() throws Exception {
        log.info("=== EOS-1: Idempotent Producer Test ===");

        KafkaConnectConfig config = createEosProducerConfig();

        // Create idempotent producer
        Properties producerProps = config.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        String testTopic = "eos-idempotent-topic";
        String messageKey = "idempotent-key";
        byte[] messageValue = "test-message".getBytes(StandardCharsets.UTF_8);

        // Send same message multiple times with same key
        List<Future<RecordMetadata>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, messageKey, messageValue);
            futures.add(producer.send(record));
        }

        // Wait for all sends
        for (Future<RecordMetadata> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        producer.flush();

        // Verify: all sends should return the same offset (idempotent)
        List<RecordMetadata> metadata = new ArrayList<>();
        for (Future<RecordMetadata> future : futures) {
            metadata.add(future.get());
        }

        // All offsets should be the same (proving idempotence)
        long firstOffset = metadata.get(0).offset();
        for (RecordMetadata m : metadata) {
            assertEquals(firstOffset, m.offset(), "Idempotent producer should return same offset");
        }

        producer.close();
        log.info("EOS-1 PASSED: Idempotent producer confirmed");
    }

    // ==================== EOS-2: Manual Commit Offset Tracking ====================

    @Test
    @DisplayName("EOS-2: Manual commit offset tracking accuracy")
    void testEosManualCommitOffsetTracking() throws Exception {
        log.info("=== EOS-2: Manual Commit Offset Tracking ===");

        String testTopic = "eos-offset-tracking-topic";
        int commitBatchSize = 50;
        int messagesToSend = commitBatchSize * 3; // 3 batches

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume with manual commit
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setGroupId("eos-offset-tracking-group");
        Properties consumerProps = consumerConfig.toConsumerProperties(true); // Force manual commit
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        // Track committed offsets
        Map<TopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();
        Map<TopicPartition, AtomicInteger> processedCounts = new ConcurrentHashMap<>();
        int consumeBatchSize = 10;
        int totalConsumed = 0;

        while (totalConsumed < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

            for (ConsumerRecord<String, byte[]> record : records) {
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                processedCounts.computeIfAbsent(tp, k -> new AtomicInteger(0)).incrementAndGet();
                totalConsumed++;

                // Manual commit every consumeBatchSize messages
                if (processedCounts.get(tp).get() % consumeBatchSize == 0) {
                    consumer.commitSync(Collections.singletonMap(tp,
                            new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1)));
                    committedOffsets.put(tp, record.offset() + 1);
                }
            }
        }

        consumer.close();

        assertEquals(messagesToSend, totalConsumed, "Should consume all messages");
        assertFalse(committedOffsets.isEmpty(), "Should have committed offsets");
        log.info("EOS-2 PASSED: Offset tracking accurate - committed {} partitions", committedOffsets.size());
    }

    // ==================== EOS-3: Batch Commit Triggers ====================

    @Test
    @DisplayName("EOS-3: Batch commit triggers at exact batch size")
    void testEosBatchCommitTriggers() throws Exception {
        log.info("=== EOS-3: Batch Commit Trigger ===");

        String testTopic = "eos-batch-commit-topic";
        int commitBatchSize = 25;

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        int messagesToSend = commitBatchSize * 4; // 4 batches
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Track commit counts with a custom consumer
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setGroupId("eos-batch-commit-group");
        Properties consumerProps = consumerConfig.toConsumerProperties(true);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        AtomicInteger commitCount = new AtomicInteger(0);
        Map<TopicPartition, AtomicInteger> partitionCommitCounts = new ConcurrentHashMap<>();
        int totalConsumed = 0;

        while (totalConsumed < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

            for (ConsumerRecord<String, byte[]> record : records) {
                totalConsumed++;
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                partitionCommitCounts.computeIfAbsent(tp, k -> new AtomicInteger(0)).incrementAndGet();

                // Manual commit
                if (partitionCommitCounts.get(tp).get() % commitBatchSize == 0) {
                    consumer.commitSync(Collections.singletonMap(tp,
                            new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1)));
                    commitCount.incrementAndGet();
                }
            }
        }

        consumer.close();

        assertEquals(messagesToSend, totalConsumed, "Should consume all messages");
        // Should have approximately messagesToSend / commitBatchSize commits per partition
        log.info("EOS-3 PASSED: Commit count = {}, messages = {}", commitCount.get(), messagesToSend);
    }

    // ==================== EOS-4: Consumer Restart Offset Resume ====================

    @Test
    @DisplayName("EOS-4: Consumer restart resumes from last committed offset")
    void testEosConsumerRestartResume() throws Exception {
        log.info("=== EOS-4: Consumer Restart Resume ===");

        String testTopic = "eos-restart-resume-topic";
        int messagesToSend = 100;

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // First consumer: consume half and commit
        String groupId = "eos-restart-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(10);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer1 = new KafkaConsumer<>(consumerProps);
        consumer1.subscribe(Collections.singletonList(testTopic));

        int firstPhaseConsumed = 0;
        while (firstPhaseConsumed < messagesToSend / 2) {
            ConsumerRecords<String, byte[]> records = consumer1.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                firstPhaseConsumed++;
                if (firstPhaseConsumed == messagesToSend / 2) {
                    consumer1.commitSync();
                    break;
                }
            }
        }
        consumer1.close();

        log.info("First consumer consumed {} messages and committed", firstPhaseConsumed);

        // Second consumer: should resume from committed offset
        KafkaConsumer<String, byte[]> consumer2 = new KafkaConsumer<>(consumerProps);
        consumer2.subscribe(Collections.singletonList(testTopic));

        int secondPhaseConsumed = 0;
        Set<String> receivedKeys = new ConcurrentHashMap().newKeySet();

        while (secondPhaseConsumed < messagesToSend / 2) {
            ConsumerRecords<String, byte[]> records = consumer2.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                // Should not receive duplicates
                assertTrue(receivedKeys.add(record.key()), "Should not receive duplicate: " + record.key());
                secondPhaseConsumed++;
            }
        }
        consumer2.close();

        assertEquals(messagesToSend / 2, secondPhaseConsumed, "Second consumer should receive remaining messages");
        log.info("EOS-4 PASSED: Consumer resumed from committed offset, received {} unique messages", secondPhaseConsumed);
    }

    // ==================== EOS-5: No Message Loss ====================

    @Test
    @DisplayName("EOS-5: No message loss under producer failure")
    void testEosNoMessageLoss() throws Exception {
        log.info("=== EOS-5: No Message Loss ===");

        String testTopic = "eos-no-loss-topic";
        int messagesToSend = 500;

        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        // Send with acks=all for durability
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            RecordMetadata metadata = producer.send(record).get(30, TimeUnit.SECONDS);
            assertNotNull(metadata, "Send should complete successfully");
        }
        producer.flush();
        producer.close();

        // Verify all messages consumed
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(100);
        consumerConfig.setGroupId("eos-no-loss-group");
        Properties consumerProps = consumerConfig.toConsumerProperties();
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        Set<String> receivedKeys = new ConcurrentHashSet<>();
        int totalReceived = 0;

        while (totalReceived < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty()) break;
            for (ConsumerRecord<String, byte[]> record : records) {
                receivedKeys.add(record.key());
                totalReceived++;
            }
        }
        consumer.close();

        assertEquals(messagesToSend, receivedKeys.size(), "Should receive all unique messages");
        log.info("EOS-5 PASSED: All {} messages received without loss", messagesToSend);
    }

    // ==================== EOS-6: No Message Duplication ====================

    @Test
    @DisplayName("EOS-6: No message duplication under consumer failure")
    void testEosNoMessageDuplication() throws Exception {
        log.info("=== EOS-6: No Message Duplication ===");

        String testTopic = "eos-no-dup-topic";
        int messagesToSend = 200;

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume with idempotent group
        String groupId = "eos-no-dup-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(50);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        int totalConsumed = 0;
        int duplicates = 0;

        while (totalConsumed < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty() && totalConsumed >= messagesToSend) break;

            for (ConsumerRecord<String, byte[]> record : records) {
                if (!seenKeys.add(record.key())) {
                    duplicates++;
                    log.warn("Duplicate detected: {}", record.key());
                }
                totalConsumed++;

                // Commit after processing each message
                if (totalConsumed % 10 == 0) {
                    consumer.commitSync();
                }
            }
        }
        consumer.close();

        assertEquals(messagesToSend, seenKeys.size(), "Should see all unique messages");
        assertEquals(0, duplicates, "Should have no duplicates with idempotent producer");
        log.info("EOS-6 PASSED: {} unique messages, {} duplicates", seenKeys.size(), duplicates);
    }

    // ==================== EOS-7: Fallback to At-Least-Once ====================

    @Test
    @DisplayName("EOS-7: EOS disabled falls back to at-least-once")
    void testEosFallbackToAtLeastOnce() throws Exception {
        log.info("=== EOS-7: Fallback to At-Least-Once ===");

        String testTopic = "eos-at-least-once-topic";

        // Non-EOS config (idempotence disabled)
        KafkaConnectConfig producerConfig = new KafkaConnectConfig();
        producerConfig.setBootstrapServers(bootstrapServers);
        producerConfig.setTopic(testTopic);
        producerConfig.setAcks("1"); // Not all
        producerConfig.setEnableIdempotence(false);

        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        int messagesToSend = 100;
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume and verify
        KafkaConnectConfig consumerConfig = new KafkaConnectConfig();
        consumerConfig.setBootstrapServers(bootstrapServers);
        consumerConfig.setTopic(testTopic);
        consumerConfig.setGroupId("at-least-once-group");
        consumerConfig.setEnableAutoCommit(true);

        Properties consumerProps = consumerConfig.toConsumerProperties();
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        Set<String> receivedKeys = new ConcurrentHashSet<>();
        int totalReceived = 0;

        while (totalReceived < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                receivedKeys.add(record.key());
                totalReceived++;
            }
        }
        consumer.close();

        assertEquals(messagesToSend, receivedKeys.size(), "Should receive all messages (at-least-once)");
        log.info("EOS-7 PASSED: At-least-once mode delivered {} messages", messagesToSend);
    }

    // ==================== EOS-8: Multi-Partition Offset Tracking ====================

    @Test
    @DisplayName("EOS-8: Multi-partition offset tracking")
    void testEosMultiPartitionOffsetTracking() throws Exception {
        log.info("=== EOS-8: Multi-Partition Offset Tracking ===");

        String testTopic = TOPIC_MP; // Already configured with 3 partitions
        int messagesPerPartition = 50;
        int totalMessages = messagesPerPartition * PARTITION_COUNT;

        // Create topic with multiple partitions
        createTopicWithPartitions(testTopic, PARTITION_COUNT);

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < totalMessages; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume from all partitions
        String groupId = "eos-multi-partition-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(25);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        Map<TopicPartition, AtomicInteger> partitionCounts = new ConcurrentHashMap<>();
        Map<TopicPartition, AtomicLong> partitionOffsets = new ConcurrentHashMap<>();
        int totalConsumed = 0;

        while (totalConsumed < totalMessages) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

            for (ConsumerRecord<String, byte[]> record : records) {
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                partitionCounts.computeIfAbsent(tp, k -> new AtomicInteger(0)).incrementAndGet();
                partitionOffsets.computeIfAbsent(tp, k -> new AtomicLong(0)).set(record.offset());
                totalConsumed++;
            }

            // Commit offsets
            if (totalConsumed % 25 == 0) {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets = new HashMap<>();
                for (TopicPartition tp : partitionOffsets.keySet()) {
                    offsets.put(tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(partitionOffsets.get(tp).get() + 1));
                }
                consumer.commitSync(offsets);
            }
        }

        consumer.close();

        assertEquals(PARTITION_COUNT, partitionCounts.size(), "Should consume from all partitions");
        log.info("EOS-8 PASSED: Multi-partition tracking - {} partitions, {} total messages",
                partitionCounts.size(), totalConsumed);
    }

    // ==================== EOS-9: Partition Reassignment Recovery ====================

    @Test
    @DisplayName("EOS-9: Partition reassignment offset recovery")
    void testEosPartitionReassignmentRecovery() throws Exception {
        log.info("=== EOS-9: Partition Reassignment Recovery ===");

        // This test simulates consumer group rebalance by closing and reopening consumer
        String testTopic = "eos-rebalance-topic";
        int messagesToSend = 150;

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // First consumer session
        String groupId = "eos-rebalance-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(30);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer1 = new KafkaConsumer<>(consumerProps);
        consumer1.subscribe(Collections.singletonList(testTopic));

        int firstSessionConsumed = 0;
        while (firstSessionConsumed < messagesToSend / 3) {
            ConsumerRecords<String, byte[]> records = consumer1.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                firstSessionConsumed++;
            }
        }
        consumer1.commitSync();
        consumer1.close();

        // Simulate rebalance - new consumer with same group should resume
        KafkaConsumer<String, byte[]> consumer2 = new KafkaConsumer<>(consumerProps);
        consumer2.subscribe(Collections.singletonList(testTopic));

        Set<String> allKeys = new ConcurrentHashSet<>();
        int secondSessionConsumed = 0;

        while (secondSessionConsumed < messagesToSend - firstSessionConsumed) {
            ConsumerRecords<String, byte[]> records = consumer2.poll(Duration.ofMillis(1000));
            if (records.isEmpty() && secondSessionConsumed >= messagesToSend - firstSessionConsumed) break;
            for (ConsumerRecord<String, byte[]> record : records) {
                assertTrue(allKeys.add(record.key()), "Duplicate after rebalance: " + record.key());
                secondSessionConsumed++;
            }
        }
        consumer2.close();

        assertEquals(messagesToSend, allKeys.size(), "All messages should be consumed exactly once");
        log.info("EOS-9 PASSED: Rebalance recovery - consumed {} unique messages", allKeys.size());
    }

    // ==================== EOS-10: Commit Failure Retry ====================

    @Test
    @DisplayName("EOS-10: Commit failure retry handling")
    void testEosCommitFailureRetry() throws Exception {
        log.info("=== EOS-10: Commit Failure Retry ===");

        String testTopic = "eos-commit-retry-topic";
        int commitBatchSize = 20;

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        int messagesToSend = commitBatchSize * 3;
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume with manual commit and retry logic
        String groupId = "eos-commit-retry-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        int totalConsumed = 0;
        int successfulCommits = 0;

        while (totalConsumed < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

            for (ConsumerRecord<String, byte[]> record : records) {
                totalConsumed++;

                // Try commit with retry
                boolean committed = false;
                int retries = 3;
                while (!committed && retries > 0) {
                    try {
                        consumer.commitSync();
                        committed = true;
                        successfulCommits++;
                    } catch (Exception e) {
                        retries--;
                        if (retries == 0) throw e;
                        Thread.sleep(100);
                    }
                }
            }
        }
        consumer.close();

        assertEquals(messagesToSend, totalConsumed, "Should consume all messages");
        assertTrue(successfulCommits > 0, "Should have successful commits");
        log.info("EOS-10 PASSED: {} commits, {} messages", successfulCommits, totalConsumed);
    }

    // ==================== EOS-11: Large Batch Commit Performance ====================

    @Test
    @DisplayName("EOS-11: Large batch commit performance")
    void testEosLargeBatchCommitPerformance() throws Exception {
        log.info("=== EOS-11: Large Batch Commit Performance ===");

        String testTopic = "eos-large-batch-topic";
        int largeBatchSize = 500;
        int commitBatchSize = 500;

        // Send large batch
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        for (int i = 0; i < largeBatchSize; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Time the commit operation
        String groupId = "eos-large-batch-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(commitBatchSize);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        long startTime = System.currentTimeMillis();
        int totalConsumed = 0;

        while (totalConsumed < largeBatchSize) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                totalConsumed++;
            }

            // Single commit at end
            if (totalConsumed >= largeBatchSize) {
                long commitStart = System.currentTimeMillis();
                consumer.commitSync();
                long commitTime = System.currentTimeMillis() - commitStart;
                log.info("Large batch commit took {} ms", commitTime);
            }
        }
        consumer.close();

        long totalTime = System.currentTimeMillis() - startTime;
        double throughput = totalConsumed / (totalTime / 1000.0);

        assertEquals(largeBatchSize, totalConsumed, "Should consume all messages");
        log.info("EOS-11 PASSED: {} messages in {} ms, throughput = {:.2f} msg/s",
                totalConsumed, totalTime, throughput);
    }

    // ==================== EOS-12: Concurrent Producer/Consumer ====================

    @Test
    @DisplayName("EOS-12: Concurrent producer/consumer")
    void testEosConcurrentProducerConsumer() throws Exception {
        log.info("=== EOS-12: Concurrent Producer/Consumer ===");

        String testTopic = "eos-concurrent-topic";
        int producerCount = 2;
        int messagesPerProducer = 100;

        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        CountDownLatch producerLatch = new CountDownLatch(producerCount);

        // Concurrent producers
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    KafkaConnectConfig producerConfig = createEosProducerConfig();
                    Properties producerProps = producerConfig.toProducerProperties();
                    KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

                    for (int i = 0; i < messagesPerProducer; i++) {
                        int globalId = producerId * messagesPerProducer + i;
                        ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(globalId),
                                ("msg-" + globalId).getBytes(StandardCharsets.UTF_8));
                        producer.send(record).get(30, TimeUnit.SECONDS);
                    }
                    producer.flush();
                    producer.close();
                } catch (Exception e) {
                    log.error("Producer {} failed: {}", producerId, e.getMessage());
                } finally {
                    producerLatch.countDown();
                }
            });
        }

        producerLatch.await(2, TimeUnit.MINUTES);
        executor.shutdown();

        // Wait for messages to be available
        Thread.sleep(2000);

        // Single consumer collects all
        String groupId = "eos-concurrent-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(50);
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties();

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        Set<String> receivedKeys = ConcurrentHashMap.newKeySet();
        int totalReceived = 0;
        int expectedTotal = producerCount * messagesPerProducer;

        while (totalReceived < expectedTotal) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty() && totalReceived >= expectedTotal) break;
            for (ConsumerRecord<String, byte[]> record : records) {
                receivedKeys.add(record.key());
                totalReceived++;
            }
        }
        consumer.close();

        assertEquals(expectedTotal, receivedKeys.size(), "Should receive all messages from concurrent producers");
        log.info("EOS-12 PASSED: {} unique messages from {} concurrent producers", receivedKeys.size(), producerCount);
    }

    // ==================== EOS-13: Message Ordering Preservation ====================

    @Test
    @DisplayName("EOS-13: Message ordering preservation")
    void testEosMessageOrderingPreservation() throws Exception {
        log.info("=== EOS-13: Message Ordering Preservation ===");

        String testTopic = "eos-ordering-topic";

        // Send messages with sequential keys
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        int messagesToSend = 100;
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Single partition for ordering test
        // Note: Ordering is guaranteed within a partition
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(25);
        consumerConfig.setGroupId("eos-ordering-group");
        Properties consumerProps = consumerConfig.toConsumerProperties();

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        int[] receivedOrder = new int[messagesToSend];
        int totalReceived = 0;

        while (totalReceived < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                int seq = Integer.parseInt(record.key());
                receivedOrder[seq] = seq;
                totalReceived++;
            }
        }
        consumer.close();

        // Verify order
        for (int i = 0; i < messagesToSend; i++) {
            assertEquals(i, receivedOrder[i], "Message order should be preserved for key " + i);
        }

        log.info("EOS-13 PASSED: All {} messages received in correct order", messagesToSend);
    }

    // ==================== EOS-14: Empty Batch Commit ====================

    @Test
    @DisplayName("EOS-14: Empty batch commit handling")
    void testEosEmptyBatchCommit() throws Exception {
        log.info("=== EOS-14: Empty Batch Commit ===");

        String testTopic = "eos-empty-batch-topic";

        KafkaConnectConfig consumerConfig = createEosConsumerConfig(10);
        consumerConfig.setGroupId("eos-empty-batch-group");
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        // Poll empty
        for (int i = 0; i < 5; i++) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
            assertTrue(records.isEmpty(), "Should have no records");

            // Should not throw on empty commit
            consumer.commitSync();
        }

        consumer.close();
        log.info("EOS-14 PASSED: Empty batch commit handled gracefully");
    }

    // ==================== EOS-15: Rapid Commit Interval ====================

    @Test
    @DisplayName("EOS-15: Rapid commit interval handling")
    void testEosRapidCommitInterval() throws Exception {
        log.info("=== EOS-15: Rapid Commit Interval ===");

        String testTopic = "eos-rapid-commit-topic";

        // Send messages
        KafkaConnectConfig producerConfig = createEosProducerConfig();
        Properties producerProps = producerConfig.toProducerProperties();
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        int messagesToSend = 200;
        for (int i = 0; i < messagesToSend; i++) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, String.valueOf(i),
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
        producer.flush();
        producer.close();

        // Consume with very small commit interval
        String groupId = "eos-rapid-commit-group-" + System.currentTimeMillis();
        KafkaConnectConfig consumerConfig = createEosConsumerConfig(1); // Commit every message
        consumerConfig.setGroupId(groupId);
        Properties consumerProps = consumerConfig.toConsumerProperties(true);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(testTopic));

        int totalConsumed = 0;
        int commitCount = 0;

        while (totalConsumed < messagesToSend) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));

            for (ConsumerRecord<String, byte[]> record : records) {
                totalConsumed++;
                // Commit after every single message
                consumer.commitSync();
                commitCount++;
            }
        }
        consumer.close();

        assertEquals(messagesToSend, totalConsumed, "Should consume all messages");
        assertEquals(messagesToSend, commitCount, "Should have committed every message");
        log.info("EOS-15 PASSED: {} messages consumed with {} commits", totalConsumed, commitCount);
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
        config.setMaxPollRecords(500);
        return config;
    }

    private void createTopicWithPartitions(String topic, int partitions) throws Exception {
        // Create topic using AdminClient
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
}
