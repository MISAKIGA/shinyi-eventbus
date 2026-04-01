package com.shinyi.eventbus.monitor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<String, AtomicLong> cumulativeLatencySum = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> cumulativeLatencyCounters = new ConcurrentHashMap<>();

    @Override
    public void increment(String bus, String topic, String name, long delta) {
        rwLock.readLock().lock();
        try {
            String key = key(bus, topic, name);
            counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
            totalCounters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void gauge(String bus, String topic, String name, long value) {
        rwLock.readLock().lock();
        try {
            String key = key(bus, topic, name);
            gauges.computeIfAbsent(key, k -> new AtomicLong()).set(value);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void recordLatency(String bus, String topic, long latencyMs) {
        rwLock.readLock().lock();
        try {
            String key = key(bus, topic, "latency");
            histograms.computeIfAbsent(key, k -> new LightweightHistogram()).record(latencyMs);
            cumulativeLatencySum.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(latencyMs);
            cumulativeLatencyCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        } finally {
            rwLock.readLock().unlock();
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

    /**
     * 原子性收集并重置指标
     * 使用写锁保证原子性，收集时同时重置计数器和直方图
     */
    public MetricsSnapshot collectAndReset() {
        rwLock.writeLock().lock();
        try {
            long timestamp = System.currentTimeMillis();

            // 收集计数器并重置
            Map<String, Long> countersSnapshot = new HashMap<>();
            counters.forEach((k, v) -> countersSnapshot.put(k, v.sumThenReset()));

            // 收集瞬时值（不重置gauges）
            Map<String, AtomicLong> gaugesSnapshot = new HashMap<>();
            gauges.forEach((k, v) -> gaugesSnapshot.put(k, new AtomicLong(v.get())));

            // 收集直方图并重置
            Map<String, MetricsSnapshot.HistogramData> histogramsSnapshot = new HashMap<>();
            histograms.forEach((k, h) -> {
                MetricsSnapshot.HistogramData data = new MetricsSnapshot.HistogramData(
                        h.getCount(), h.getMean(), h.getP50(), h.getP90(), h.getP99());
                h.reset();
                histogramsSnapshot.put(k, data);
            });

            return new MetricsSnapshot(timestamp, countersSnapshot, gaugesSnapshot, histogramsSnapshot);
        } finally {
            rwLock.writeLock().unlock();
        }
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
