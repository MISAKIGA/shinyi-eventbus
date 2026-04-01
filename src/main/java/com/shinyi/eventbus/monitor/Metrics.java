package com.shinyi.eventbus.monitor;

/**
 * 指标接口
 */
public interface Metrics {
    /**
     * 计数器 - 事件总数
     *
     * @param bus   总线名称
     * @param topic 主题
     * @param name  指标名称
     * @param delta 增量值
     */
    void increment(String bus, String topic, String name, long delta);

    /**
     * 瞬时值 - 当前队列深度等
     *
     * @param bus   总线名称
     * @param topic 主题
     * @param name  指标名称
     * @param value 当前值
     */
    void gauge(String bus, String topic, String name, long value);

    /**
     * 延迟记录
     *
     * @param bus       总线名称
     * @param topic     主题
     * @param latencyMs 延迟毫秒数
     */
    void recordLatency(String bus, String topic, long latencyMs);

    /**
     * 收集所有指标（JSON友好）
     *
     * @return 指标快照
     */
    MetricsSnapshot collect();

    /**
     * 重置增量值
     */
    void reset();
}
