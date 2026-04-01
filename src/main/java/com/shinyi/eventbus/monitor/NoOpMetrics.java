package com.shinyi.eventbus.monitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 空实现 - 禁用监控时使用
 * 所有方法为空实现，collect() 返回空快照
 */
public class NoOpMetrics implements Metrics {

    private static final MetricsSnapshot EMPTY_SNAPSHOT = new MetricsSnapshot(
            System.currentTimeMillis(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap()
    );

    @Override
    public void increment(String bus, String topic, String name, long delta) {
        // 空实现
    }

    @Override
    public void gauge(String bus, String topic, String name, long value) {
        // 空实现
    }

    @Override
    public void recordLatency(String bus, String topic, long latencyMs) {
        // 空实现
    }

    @Override
    public MetricsSnapshot collect() {
        return EMPTY_SNAPSHOT;
    }

    @Override
    public void reset() {
        // 空实现
    }
}
