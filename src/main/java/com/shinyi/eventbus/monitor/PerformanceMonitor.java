package com.shinyi.eventbus.monitor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 性能监控器 - 用于追踪EventBus各环节的耗时
 *
 * 功能：
 * 1. 方法级耗时统计
 * 2. 调用计数
 * 3. 可开关控制（通过系统属性或配置）
 *
 * 使用方式：
 * - 开启：-Dcom.shinyi.eventbus.performance.monitor=true
 * - 或设置环境变量：SHINYI_EVENTBUS_PERF_MONITOR=true
 */
@Slf4j
public class PerformanceMonitor {

    private static volatile boolean ENABLED = false;

    static {
        String prop = System.getProperty("com.shinyi.eventbus.performance.monitor");
        if (prop == null) {
            prop = System.getenv("SHINYI_EVENTBUS_PERF_MONITOR");
        }
        ENABLED = "true".equalsIgnoreCase(prop) || "1".equals(prop);
    }

    /**
     * 是否启用监控
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 开启监控
     */
    public static void enable() {
        ENABLED = true;
        log.info("Performance monitoring ENABLED");
    }

    /**
     * 关闭监控
     */
    public static void disable() {
        ENABLED = false;
        log.info("Performance monitoring DISABLED");
    }

    /**
     * 性能指标快照
     */
    @Data
    public static class Metric {
        private final String name;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalNs = new AtomicLong(0);
        private final AtomicLong maxNs = new AtomicLong(0);
        private final AtomicLong minNs = new AtomicLong(Long.MAX_VALUE);

        public void record(long durationNs) {
            count.incrementAndGet();
            totalNs.addAndGet(durationNs);
            maxNs.updateAndGet(current -> Math.max(current, durationNs));
            minNs.updateAndGet(current -> Math.min(current, durationNs));
        }

        public long getAvgNs() {
            long c = count.get();
            return c > 0 ? totalNs.get() / c : 0;
        }

        public double getAvgMs() {
            return getAvgNs() / 1_000_000.0;
        }

        public double getMaxMs() {
            return maxNs.get() / 1_000_000.0;
        }

        public double getMinMs() {
            long min = minNs.get();
            return min == Long.MAX_VALUE ? 0 : min / 1_000_000.0;
        }

        public double getThroughput() {
            return count.get();
        }

        public long getCountValue() {
            return count.get();
        }
    }

    // 监控指标存储
    private static final java.util.Map<String, Metric> METRICS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 获取或创建指标
     */
    public static Metric getOrCreateMetric(String name) {
        return METRICS.computeIfAbsent(name, Metric::new);
    }

    /**
     * 记录耗时
     */
    public static void record(String name, long durationNs) {
        if (ENABLED) {
            getOrCreateMetric(name).record(durationNs);
        }
    }

    /**
     * 执行并计时
     */
    public static <T> T time(String name, Supplier<T> supplier) {
        if (!ENABLED) {
            return supplier.get();
        }
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    /**
     * 执行并计时（无返回值）
     */
    public static void time(String name, Runnable runnable) {
        if (!ENABLED) {
            runnable.run();
            return;
        }
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    /**
     * 打印所有指标统计
     */
    public static void printStats() {
        if (!ENABLED) {
            return;
        }
        log.info("========== Performance Stats ==========");
        METRICS.forEach((name, metric) -> {
            log.info("{}: count={}, avg={:.3f}ms, min={:.3f}ms, max={:.3f}ms",
                    name, metric.getCountValue(), metric.getAvgMs(), metric.getMinMs(), metric.getMaxMs());
        });
        log.info("======================================");
    }

    /**
     * 重置所有指标
     */
    public static void reset() {
        METRICS.clear();
    }

    /**
     * 获取格式化报告
     */
    public static String getReport() {
        if (!ENABLED || METRICS.isEmpty()) {
            return "Performance monitoring is disabled or no metrics recorded.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Performance Report ==========\n");
        METRICS.forEach((name, metric) -> {
            sb.append(String.format("%-40s | count=%-10d | avg=%.3fms | min=%.3fms | max=%.3fms\n",
                    name, metric.getCountValue(), metric.getAvgMs(), metric.getMinMs(), metric.getMaxMs()));
        });
        sb.append("========================================\n");
        return sb.toString();
    }
}
