package com.shinyi.eventbus.monitor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SimpleMetricsTest {

    @Test
    public void testIncrement() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.published", 1);
        metrics.increment("kafka", "topic1", "events.published", 2);

        MetricsSnapshot snapshot = metrics.collect();
        // 验证能收集到数据
        assertNotNull(snapshot);
        assertNotNull(snapshot.getCounters());
    }

    @Test
    public void testRecordLatency() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.recordLatency("kafka", "topic1", 10);
        metrics.recordLatency("kafka", "topic1", 20);

        MetricsSnapshot snapshot = metrics.collect();
        // 验证能收集到延迟数据
        assertNotNull(snapshot);
        assertNotNull(snapshot.getHistograms());
    }

    @Test
    public void testGracefulDegradation() {
        // 测试 NoOpMetrics 的优雅降级
        Metrics metrics = new NoOpMetrics();
        metrics.increment("kafka", "topic1", "events.published", 1);
        metrics.recordLatency("kafka", "topic1", 10);
        // 不应抛出异常
    }

    @Test
    public void testGauge() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.gauge("kafka", "topic1", "queue.depth", 100);

        MetricsSnapshot snapshot = metrics.collect();
        assertNotNull(snapshot);
        assertNotNull(snapshot.getGauges());
    }

    @Test
    public void testReset() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.published", 10);
        metrics.increment("kafka", "topic1", "events.published", 5);

        // Collect first to get initial count
        MetricsSnapshot snapshot1 = metrics.collect();
        assertNotNull(snapshot1);

        // Reset
        metrics.reset();

        // Collect again - counters should be reset
        MetricsSnapshot snapshot2 = metrics.collect();
        assertNotNull(snapshot2);
    }

    @Test
    public void testGetTotalCount() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.published", 10);
        metrics.increment("kafka", "topic1", "events.published", 5);

        long total = metrics.getTotalCount("kafka", "topic1", "events.published");
        assertEquals(15, total);
    }

    @Test
    public void testGetTotalCountNonExistent() {
        SimpleMetrics metrics = new SimpleMetrics();
        long total = metrics.getTotalCount("kafka", "topic1", "non.existent");
        assertEquals(0, total);
    }

    @Test
    public void testGetGauge() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.gauge("kafka", "topic1", "queue.depth", 100);

        long gauge = metrics.getGauge("kafka", "topic1", "queue.depth");
        assertEquals(100, gauge);
    }

    @Test
    public void testGetGaugeNonExistent() {
        SimpleMetrics metrics = new SimpleMetrics();
        long gauge = metrics.getGauge("kafka", "topic1", "non.existent");
        assertEquals(0, gauge);
    }

    @Test
    public void testCollectReturnsSnapshotWithTimestamp() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsSnapshot snapshot = metrics.collect();
        assertTrue(snapshot.getTimestamp() > 0);
    }

    @Test
    public void testCollectReturnsEmptySnapshotWhenNoData() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsSnapshot snapshot = metrics.collect();
        assertNotNull(snapshot.getCounters());
        assertNotNull(snapshot.getGauges());
        assertNotNull(snapshot.getHistograms());
    }

    @Test
    public void testIncrementMultipleTopics() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.published", 1);
        metrics.increment("kafka", "topic2", "events.published", 2);
        metrics.increment("rabbitmq", "topic1", "events.published", 3);

        MetricsSnapshot snapshot = metrics.collect();
        Map<String, Long> counters = snapshot.getCounters();

        assertEquals(3, counters.size());
        assertEquals(Long.valueOf(1), counters.get("kafka:topic1:events.published"));
        assertEquals(Long.valueOf(2), counters.get("kafka:topic2:events.published"));
        assertEquals(Long.valueOf(3), counters.get("rabbitmq:topic1:events.published"));
    }

    @Test
    public void testRecordLatencyHistogram() {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.recordLatency("kafka", "topic1", 10);
        metrics.recordLatency("kafka", "topic1", 20);
        metrics.recordLatency("kafka", "topic1", 30);

        MetricsSnapshot snapshot = metrics.collect();
        Map<String, MetricsSnapshot.HistogramData> histograms = snapshot.getHistograms();

        assertTrue(histograms.containsKey("kafka:topic1:latency"));
        MetricsSnapshot.HistogramData data = histograms.get("kafka:topic1:latency");
        assertEquals(3, data.getCount());
        assertEquals(20.0, data.getMean(), 0.01);
    }

    @Test
    public void testAtomicCollectAndReset() throws Exception {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.consumed", 100);
        metrics.recordLatency("kafka", "topic1", 50);

        // First collect - should have data
        MetricsSnapshot snap1 = ((SimpleMetrics) metrics).collectAndReset();
        assertEquals(100L, snap1.getCounters().get("kafka:topic1:events.consumed"));

        // After reset - counters should be zero
        MetricsSnapshot snap2 = metrics.collect();
        assertEquals(0L, snap2.getCounters().get("kafka:topic1:events.consumed"));
    }

    @Test
    public void testCumulativeCountersPersistAfterReset() throws Exception {
        SimpleMetrics metrics = new SimpleMetrics();
        metrics.increment("kafka", "topic1", "events.consumed", 100);

        // Total count should persist after reset
        metrics.reset();
        long total = metrics.getTotalCount("kafka", "topic1", "events.consumed");
        assertEquals(100, total);
    }
}
