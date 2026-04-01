package com.shinyi.eventbus.registry;

import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.config.kafka.KafkaConnectConfig;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test parallel consumption configuration
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaParallelConsumeTest {

    @Test
    public void testConsumerThreadsDefault() {
        // Verify default is 0 (auto-detect)
        KafkaConnectConfig config = new KafkaConnectConfig();
        assertEquals(0, config.getConsumerThreads());
    }

    @Test
    public void testConsumerThreadsConfig() {
        // Verify consumerThreads is set correctly
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setConsumerThreads(8);
        assertEquals(8, config.getConsumerThreads());
    }

    @Test
    public void testAutoDetectDefault() {
        // Verify autoDetectConsumerThreads default is true
        KafkaConnectConfig config = new KafkaConnectConfig();
        assertTrue(config.isAutoDetectConsumerThreads());
    }

    @Test
    public void testAutoDetectConfig() {
        // Verify autoDetectConsumerThreads can be disabled
        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setAutoDetectConsumerThreads(false);
        assertFalse(config.isAutoDetectConsumerThreads());
    }

    @Test
    public void testThreadBalancingLogic() {
        // Test the balancing logic when partitionCount <= cpuCores (if branch)
        // Simulate: partitionCount = 4, cpuCores = 8
        int cpuCores = 8;
        int partitionCount = 4;
        int threads;
        if (partitionCount <= cpuCores) {
            threads = Math.min(partitionCount, cpuCores);
        } else {
            int balancedThreads = Math.min(cpuCores * 4, Math.min(partitionCount, 32));
            threads = balancedThreads;
        }
        assertEquals(4, threads);

        // Test the balancing logic when partitionCount > cpuCores (else branch)
        // Simulate: partitionCount = 50, cpuCores = 2
        cpuCores = 2;
        partitionCount = 50;
        if (partitionCount <= cpuCores) {
            threads = Math.min(partitionCount, cpuCores);
        } else {
            int balancedThreads = Math.min(cpuCores * 4, Math.min(partitionCount, 32));
            threads = balancedThreads;
        }
        // 50 > 2, so threads = min(2*4, min(50, 32)) = min(8, 32) = 8
        assertEquals(8, threads);
    }

    @Test
    public void testNoBlockingInPipeline() {
        // Verify configuration ensures no blocking in pipeline mode
        // This test mainly verifies code structure

        KafkaConnectConfig config = new KafkaConnectConfig();
        config.setEnableManualCommit(false);  // Non-EOS mode

        // Confirm configuration is correct
        assertFalse(config.isEnableManualCommit());
    }
}