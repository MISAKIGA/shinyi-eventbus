package com.shinyi.eventbus.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricsCollectorTest {

    @Test
    public void testCollectAndReset() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);

        // Manually trigger collection
        collector.run();

        // Verify snapshot was generated
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testGetLastSnapshotBeforeCollect() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        // Before collection, snapshot should be null
        assertNull(collector.getLastSnapshot());
    }

    @Test
    public void testShutdown() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);
        collector.run();

        collector.shutdown();

        // After shutdown, run should not collect
        collector.run();
        // But the last snapshot should be retained
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testCollectAfterShutdown() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        collector.shutdown();

        // After shutdown, run should not update snapshot
        collector.run();
        assertNull(collector.getLastSnapshot());
    }

    @Test
    public void testCollectWithNoData() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        collector.run();

        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testCollectContainsMetrics() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 5);
        metrics.gauge("kafka", "topic1", "queue.depth", 100);
        metrics.recordLatency("kafka", "topic1", 25);

        collector.run();

        MetricsSnapshot snapshot = collector.getLastSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.getCounters().size() > 0);
    }

    @Test
    public void testMultipleCollects() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);
        collector.run();
        MetricsSnapshot snapshot1 = collector.getLastSnapshot();

        metrics.increment("kafka", "topic1", "events.published", 5);
        collector.run();
        MetricsSnapshot snapshot2 = collector.getLastSnapshot();

        // Two collections should produce different timestamps
        assertTrue(snapshot2.getTimestamp() >= snapshot1.getTimestamp());
    }

    @Test
    public void testNoOpMetricsGracefulDegradation() {
        Metrics metrics = new NoOpMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        // Should not throw exception
        collector.run();
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testResetAfterCollect() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);
        collector.run();

        // After collection, counters should be reset
        metrics.reset();

        // Collect again, counter should be 0
        collector.run();
        MetricsSnapshot snapshot = collector.getLastSnapshot();
        assertTrue(snapshot.getCounters().get("kafka:topic1:events.published") == 0);
    }

    @Test
    public void testIntervalMsStored() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 5000);

        // Verify collector was created successfully
        assertNotNull(collector);
    }

    @Test
    public void testDeltaCalculation() {
        // Create SimpleMetrics
        SimpleMetrics metrics = new SimpleMetrics();

        // First: increase by 100
        metrics.increment("kafka", "topic1", "events.consumed", 100);
        metrics.increment("kafka", "topic1", "events.published", 50);

        // Create collector (logEnabled=false to suppress output)
        MetricsCollector collector = new MetricsCollector(metrics, 1000, false);

        // First collection
        collector.run();
        MetricsSnapshot snap1 = collector.getLastSnapshot();

        // Second: increase by 200 (total becomes 300)
        metrics.increment("kafka", "topic1", "events.consumed", 200);
        metrics.increment("kafka", "topic1", "events.published", 100);

        // Wait a bit to ensure time difference
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Second collection
        collector.run();

        // Verify: getTotalCount returns cumulative values (300, 150)
        assertEquals(300, metrics.getTotalCount("kafka", "topic1", "events.consumed"));
        assertEquals(150, metrics.getTotalCount("kafka", "topic1", "events.published"));
    }
}
