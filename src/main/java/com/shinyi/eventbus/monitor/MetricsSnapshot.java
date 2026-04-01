package com.shinyi.eventbus.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标快照
 * 包含时间戳和聚合后的指标数据
 */
public class MetricsSnapshot {

    private final long timestamp;
    private final Map<String, Long> counters;
    private final Map<String, AtomicLong> gauges;
    private final Map<String, HistogramData> histograms;
    private final Map<String, Long> cumulativeCounters;
    private final Map<String, HistogramData> cumulativeHistograms;

    /**
     * 空快照构造方法
     */
    public MetricsSnapshot() {
        this.timestamp = 0;
        this.counters = new HashMap<>();
        this.gauges = new HashMap<>();
        this.histograms = new HashMap<>();
        this.cumulativeCounters = new HashMap<>();
        this.cumulativeHistograms = new HashMap<>();
    }

    public MetricsSnapshot(long timestamp, Map<String, Long> counters,
                          Map<String, AtomicLong> gauges,
                          Map<String, HistogramData> histograms) {
        this.timestamp = timestamp;
        this.counters = counters;
        this.gauges = gauges;
        this.histograms = histograms;
        this.cumulativeCounters = new HashMap<>();
        this.cumulativeHistograms = new HashMap<>();
    }

    public MetricsSnapshot(long timestamp,
                           Map<String, Long> counters,
                           Map<String, AtomicLong> gauges,
                           Map<String, HistogramData> histograms,
                           Map<String, Long> cumulativeCounters,
                           Map<String, HistogramData> cumulativeHistograms) {
        this.timestamp = timestamp;
        this.counters = counters;
        this.gauges = gauges;
        this.histograms = histograms;
        this.cumulativeCounters = cumulativeCounters;
        this.cumulativeHistograms = cumulativeHistograms;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<String, Long> getCounters() {
        return counters;
    }

    public Map<String, AtomicLong> getGauges() {
        return gauges;
    }

    public Map<String, HistogramData> getHistograms() {
        return histograms;
    }

    public Map<String, Long> getCumulativeCounters() {
        return cumulativeCounters;
    }

    public Map<String, HistogramData> getCumulativeHistograms() {
        return cumulativeHistograms;
    }

    /**
     * 直方图数据
     */
    public static class HistogramData {
        private final long count;
        private final double mean;
        private final long p50;
        private final long p90;
        private final long p99;

        public HistogramData(long count, double mean, long p50, long p90, long p99) {
            this.count = count;
            this.mean = mean;
            this.p50 = p50;
            this.p90 = p90;
            this.p99 = p99;
        }

        public long getCount() {
            return count;
        }

        public double getMean() {
            return mean;
        }

        public long getP50() {
            return p50;
        }

        public long getP90() {
            return p90;
        }

        public long getP99() {
            return p99;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("count", count);
            map.put("mean", mean);
            map.put("p50", p50);
            map.put("p90", p90);
            map.put("p99", p99);
            return map;
        }
    }

    /**
     * 转换为Map用于JSON序列化
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", timestamp);

        Map<String, Object> countersMap = new HashMap<>();
        counters.forEach((k, v) -> countersMap.put(k, v));
        result.put("counters", countersMap);

        Map<String, Object> gaugesMap = new HashMap<>();
        gauges.forEach((k, v) -> gaugesMap.put(k, v.get()));
        result.put("gauges", gaugesMap);

        Map<String, Object> histogramsMap = new HashMap<>();
        histograms.forEach((k, v) -> histogramsMap.put(k, v.toMap()));
        result.put("histograms", histogramsMap);

        Map<String, Object> cumulativeCountersMap = new HashMap<>();
        cumulativeCounters.forEach((k, v) -> cumulativeCountersMap.put(k, v));
        result.put("cumulativeCounters", cumulativeCountersMap);

        Map<String, Object> cumulativeHistogramsMap = new HashMap<>();
        cumulativeHistograms.forEach((k, v) -> cumulativeHistogramsMap.put(k, v.toMap()));
        result.put("cumulativeHistograms", cumulativeHistogramsMap);

        return result;
    }
}
