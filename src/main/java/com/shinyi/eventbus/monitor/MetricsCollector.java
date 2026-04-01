package com.shinyi.eventbus.monitor;

import lombok.extern.slf4j.Slf4j;

/**
 * 指标收集器
 * 定时收集指标并重置增量值
 */
@Slf4j
public class MetricsCollector implements Runnable {
    private final Metrics metrics;
    private final long intervalMs;
    private volatile boolean running = true;
    private volatile MetricsSnapshot lastSnapshot;

    public MetricsCollector(Metrics metrics, long intervalMs) {
        this.metrics = metrics;
        this.intervalMs = intervalMs;
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            lastSnapshot = metrics.collect();
            metrics.reset(); // 重置增量值（保留累计值）
        } catch (Throwable t) {
            // 优雅降级
            log.warn("Failed to collect metrics", t);
        }
    }

    public MetricsSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public void shutdown() {
        running = false;
    }
}
