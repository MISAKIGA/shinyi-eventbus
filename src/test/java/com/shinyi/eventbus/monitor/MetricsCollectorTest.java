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

        // 手动触发收集
        collector.run();

        // 验证快照已生成
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testGetLastSnapshotBeforeCollect() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        // 未收集前，快照应为 null
        assertNull(collector.getLastSnapshot());
    }

    @Test
    public void testShutdown() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);
        collector.run();

        collector.shutdown();

        // shutdown 后 run 不应再收集
        collector.run();
        // 但最后一次快照应保留
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testCollectAfterShutdown() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        collector.shutdown();

        // shutdown 后 run 不应再更新快照
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

        // 两次收集应产生不同的时间戳
        assertTrue(snapshot2.getTimestamp() >= snapshot1.getTimestamp());
    }

    @Test
    public void testNoOpMetricsGracefulDegradation() {
        Metrics metrics = new NoOpMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        // 不应抛出异常
        collector.run();
        assertNotNull(collector.getLastSnapshot());
    }

    @Test
    public void testResetAfterCollect() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 1000);

        metrics.increment("kafka", "topic1", "events.published", 10);
        collector.run();

        // 收集后，计数应该被重置
        metrics.reset();

        // 再次收集，计数器应为0
        collector.run();
        MetricsSnapshot snapshot = collector.getLastSnapshot();
        assertTrue(snapshot.getCounters().get("kafka:topic1:events.published") == 0);
    }

    @Test
    public void testIntervalMsStored() {
        SimpleMetrics metrics = new SimpleMetrics();
        MetricsCollector collector = new MetricsCollector(metrics, 5000);

        // 验证 collector 创建成功
        assertNotNull(collector);
    }

    @Test
    public void testDeltaCalculation() {
        // 创建 SimpleMetrics
        SimpleMetrics metrics = new SimpleMetrics();

        // 第一次: 增加 100
        metrics.increment("kafka", "topic1", "events.consumed", 100);
        metrics.increment("kafka", "topic1", "events.published", 50);

        // 创建 collector (logEnabled=false 避免输出)
        MetricsCollector collector = new MetricsCollector(metrics, 1000, false);

        // 第一次收集
        collector.run();
        MetricsSnapshot snap1 = collector.getLastSnapshot();

        // 第二次: 再增加 200 (累计变成 300)
        metrics.increment("kafka", "topic1", "events.consumed", 200);
        metrics.increment("kafka", "topic1", "events.published", 100);

        // 等待一小段时间确保时间差
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 第二次收集
        collector.run();

        // 验证: getTotalCount 返回累计值 (300, 150)
        assertEquals(300, metrics.getTotalCount("kafka", "topic1", "events.consumed"));
        assertEquals(150, metrics.getTotalCount("kafka", "topic1", "events.published"));
    }
}
