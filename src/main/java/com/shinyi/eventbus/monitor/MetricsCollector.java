package com.shinyi.eventbus.monitor;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 指标收集器
 * 定时收集指标并重置增量值
 */
@Slf4j
public class MetricsCollector implements Runnable {
    private final Metrics metrics;
    private final long intervalMs;
    private final boolean logEnabled;
    private final ResetStrategy resetStrategy;
    private final long resetIntervalMs;
    private volatile boolean running = true;
    private volatile MetricsSnapshot lastSnapshot;
    private volatile long lastCollectTime = System.currentTimeMillis();
    private volatile long lastResetTime = System.currentTimeMillis();

    public MetricsCollector(Metrics metrics, long intervalMs) {
        this(metrics, intervalMs, true, ResetStrategy.INTERVAL, 86400000);
    }

    public MetricsCollector(Metrics metrics, long intervalMs, boolean logEnabled) {
        this(metrics, intervalMs, logEnabled, ResetStrategy.INTERVAL, 86400000);
    }

    public MetricsCollector(Metrics metrics, long intervalMs, boolean logEnabled,
                           ResetStrategy resetStrategy, long resetIntervalMs) {
        this.metrics = metrics;
        this.intervalMs = intervalMs;
        this.logEnabled = logEnabled;
        this.resetStrategy = resetStrategy;
        this.resetIntervalMs = resetIntervalMs;
        this.lastResetTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            long now = System.currentTimeMillis();

            // Check if we should reset - use atomic collectAndReset
            if (shouldReset() && metrics instanceof SimpleMetrics) {
                ((SimpleMetrics) metrics).collectAndReset();
                lastResetTime = now;
            }

            // Collect without reset
            lastSnapshot = metrics.collect();
            lastCollectTime = now;

            // 定期日志输出
            if (logEnabled && log.isInfoEnabled()) {
                printMetricsLog(now);
            }
        } catch (Throwable t) {
            // 优雅降级
            log.warn("Failed to collect metrics", t);
        }
    }

    private boolean shouldReset() {
        if (resetStrategy == ResetStrategy.NEVER || resetStrategy == ResetStrategy.MANUAL) {
            return false;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastResetTime;

        switch (resetStrategy) {
            case DAILY:
                return hasDayPassed(lastResetTime, now);
            case HOURLY:
                return elapsed >= 3600000;
            case INTERVAL:
            default:
                return elapsed >= resetIntervalMs;
        }
    }

    private boolean hasDayPassed(long lastReset, long now) {
        return (now - lastReset) > 23 * 3600000;
    }

    private void printMetricsLog(long now) {
        if (lastSnapshot == null) return;

        // 计算时间间隔（秒）
        double intervalSec = (now - lastCollectTime) / 1000.0;
        if (intervalSec <= 0) intervalSec = 1;

        // 按 topic 聚合指标
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== EventBus Metrics ==========\n");

        // 处理 counters: events.consumed, events.published
        Map<String, Long> counters = lastSnapshot.getCounters();
        Map<String, MetricsSnapshot.HistogramData> histograms = lastSnapshot.getHistograms();

        // 按 topic 分组
        Map<String, TopicMetrics> topicMetrics = new HashMap<>();

        counters.forEach((key, value) -> {
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                String topic = parts[1];
                String metric = parts[2];

                TopicMetrics tm = topicMetrics.computeIfAbsent(topic, k -> new TopicMetrics(topic));
                if ("events.consumed".equals(metric)) {
                    tm.consumed = value;
                } else if ("events.published".equals(metric)) {
                    tm.published = value;
                }
            }
        });

        histograms.forEach((key, hist) -> {
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                String topic = parts[1];
                TopicMetrics tm = topicMetrics.computeIfAbsent(topic, k -> new TopicMetrics(topic));
                tm.latencyMean = hist.getMean();
                tm.latencyP99 = hist.getP99();
            }
        });

        // 计算全局吞吐
        long totalConsumed = 0;
        long totalPublished = 0;

        for (Map.Entry<String, TopicMetrics> entry : topicMetrics.entrySet()) {
            TopicMetrics tm = entry.getValue();
            totalConsumed += tm.consumed;
            totalPublished += tm.published;

            // 每秒吞吐
            double consumedPerSec = tm.consumed / intervalSec;
            double publishedPerSec = tm.published / intervalSec;

            sb.append(String.format("%s [%.1fs]:\n", entry.getKey(), intervalSec));
            sb.append(String.format("  消费: %d msg (%.2f/s)\n", tm.consumed, consumedPerSec));
            sb.append(String.format("  发布: %d msg (%.2f/s)\n", tm.published, publishedPerSec));
            if (tm.latencyMean > 0) {
                sb.append(String.format("  延迟: avg=%.2fms p99=%dms\n", tm.latencyMean, tm.latencyP99));
            }
        }

        // 总体统计
        double totalConsumedPerSec = totalConsumed / intervalSec;
        double totalPublishedPerSec = totalPublished / intervalSec;
        sb.append("----------------------------------\n");
        sb.append(String.format("Total: consumed=%.2f/s published=%.2f/s\n", totalConsumedPerSec, totalPublishedPerSec));
        sb.append("==================================\n");

        log.info(sb.toString());

        // 更新上次统计
        lastCollectTime = now;
    }

    private static class TopicMetrics {
        String topic;
        long consumed = 0;
        long published = 0;
        double latencyMean = 0;
        long latencyP99 = 0;

        TopicMetrics(String topic) {
            this.topic = topic;
        }
    }

    public MetricsSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public void shutdown() {
        running = false;
    }

    public void reset() {
        if (metrics instanceof SimpleMetrics) {
            ((SimpleMetrics) metrics).collectAndReset();
            lastResetTime = System.currentTimeMillis();
        }
    }
}
