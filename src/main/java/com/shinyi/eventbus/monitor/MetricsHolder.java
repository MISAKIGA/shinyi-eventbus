package com.shinyi.eventbus.monitor;

/**
 * Metrics持有者 - 提供对Metrics实例的静态访问
 * 类似于PerformanceMonitor的模式，支持优雅降级
 *
 * 使用方式：
 * 1. MonitoringAutoConfiguration初始化后设置Metrics实例
 * 2. 如果未设置或设置失败，使用NoOpMetrics作为默认实现
 */
public class MetricsHolder {

    private static volatile Metrics instance = new NoOpMetrics();

    private MetricsHolder() {
    }

    /**
     * 设置Metrics实例
     * @param metrics Metrics实例
     */
    public static void setMetrics(Metrics metrics) {
        if (metrics != null) {
            instance = metrics;
        }
    }

    /**
     * 获取Metrics实例
     * @return Metrics实例，如果未设置则返回NoOpMetrics
     */
    public static Metrics getMetrics() {
        return instance;
    }

    /**
     * 快捷方法：增加计数器
     */
    public static void increment(String bus, String topic, String name, long delta) {
        try {
            instance.increment(bus, topic, name, delta);
        } catch (Exception e) {
            // 优雅降级
        }
    }

    /**
     * 快捷方法：记录瞬时值
     */
    public static void gauge(String bus, String topic, String name, long value) {
        try {
            instance.gauge(bus, topic, name, value);
        } catch (Exception e) {
            // 优雅降级
        }
    }

    /**
     * 快捷方法：记录延迟
     */
    public static void recordLatency(String bus, String topic, long latencyMs) {
        try {
            instance.recordLatency(bus, topic, latencyMs);
        } catch (Exception e) {
            // 优雅降级
        }
    }
}
