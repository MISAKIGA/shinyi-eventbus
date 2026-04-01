package com.shinyi.eventbus.monitor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 简单指标实现
 * 使用ConcurrentHashMap存储，LongAdder计数，LightweightHistogram延迟
 * 按 bus/topic/histogram-name 三级索引
 */
public class SimpleMetrics implements Metrics {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LightweightHistogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totalCounters = new ConcurrentHashMap<>();

    @Override
    public void increment(String bus, String topic, String name, long delta) {
        try {
            String key = key(bus, topic, name);
            counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
            totalCounters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
        } catch (Exception e) {
            // 优雅降级，不影响业务
        }
    }

    @Override
    public void gauge(String bus, String topic, String name, long value) {
        try {
            String key = key(bus, topic, name);
            gauges.computeIfAbsent(key, k -> new AtomicLong()).set(value);
        } catch (Exception e) {
            // 优雅降级，不影响业务
        }
    }

    @Override
    public void recordLatency(String bus, String topic, long latencyMs) {
        try {
            String key = key(bus, topic, "latency");
            histograms.computeIfAbsent(key, k -> new LightweightHistogram()).record(latencyMs);
        } catch (Exception e) {
            // 优雅降级，不影响业务
        }
    }

    @Override
    public MetricsSnapshot collect() {
        long timestamp = System.currentTimeMillis();

        // 收集计数器
        Map<String, Long> countersSnapshot = new HashMap<>();
        counters.forEach((k, v) -> countersSnapshot.put(k, v.sum()));

        // 收集瞬时值
        Map<String, AtomicLong> gaugesSnapshot = new HashMap<>();
        gauges.forEach((k, v) -> gaugesSnapshot.put(k, new AtomicLong(v.get())));

        // 收集直方图
        Map<String, MetricsSnapshot.HistogramData> histogramsSnapshot = new HashMap<>();
        histograms.forEach((k, h) -> {
            MetricsSnapshot.HistogramData data = new MetricsSnapshot.HistogramData(
                    h.getCount(),
                    h.getMean(),
                    h.getP50(),
                    h.getP90(),
                    h.getP99()
            );
            histogramsSnapshot.put(k, data);
        });

        return new MetricsSnapshot(timestamp, countersSnapshot, gaugesSnapshot, histogramsSnapshot);
    }

    @Override
    public void reset() {
        try {
            counters.forEach((k, v) -> v.reset());
        } catch (Exception e) {
            // 优雅降级
        }
    }

    /**
     * 获取总计数（不重置）
     */
    public long getTotalCount(String bus, String topic, String name) {
        try {
            String key = key(bus, topic, name);
            AtomicLong total = totalCounters.get(key);
            return total != null ? total.get() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取当前瞬时值
     */
    public long getGauge(String bus, String topic, String name) {
        try {
            String key = key(bus, topic, name);
            AtomicLong gauge = gauges.get(key);
            return gauge != null ? gauge.get() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String key(String bus, String topic, String name) {
        return bus + ":" + topic + ":" + name;
    }
}
