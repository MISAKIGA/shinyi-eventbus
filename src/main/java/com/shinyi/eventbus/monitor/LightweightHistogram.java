package com.shinyi.eventbus.monitor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量级分桶直方图
 * 使用固定数量桶实现近似的百分位计算
 * 内存安全，不会OOM
 */
public class LightweightHistogram {
    private static final int BUCKET_COUNT = 100;
    private static final long BUCKET_SIZE = 10; // 10ms per bucket

    private final LongAdder[] buckets = new LongAdder[BUCKET_COUNT];
    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final AtomicLong maxValue = new AtomicLong(0);

    public LightweightHistogram() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /**
     * 记录一个值
     *
     * @param value 要记录的值（毫秒）
     */
    public void record(long value) {
        if (value < 0) return;
        count.increment();
        sum.add(value);
        maxValue.updateAndGet(current -> Math.max(current, value));
        int bucket = Math.min((int) (value / BUCKET_SIZE), BUCKET_COUNT - 1);
        buckets[bucket].increment();
    }

    /**
     * 获取近似百分位
     *
     * @param p 百分位（0.0-1.0）
     * @return 近似百分位值
     */
    public long getPercentile(double p) {
        long target = (long) (count.sum() * p);
        if (target <= 0) return 0;
        long cumulative = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                return i * BUCKET_SIZE;
            }
        }
        return maxValue.get();
    }

    /**
     * 获取平均值
     *
     * @return 平均值
     */
    public double getMean() {
        long c = count.sum();
        return c > 0 ? (double) sum.sum() / c : 0;
    }

    /**
     * 获取记录数量
     *
     * @return 记录数量
     */
    public long getCount() {
        return count.sum();
    }

    /**
     * 获取最大值
     *
     * @return 最大值
     */
    public long getMaxValue() {
        return maxValue.get();
    }

    /**
     * 获取p50百分位
     *
     * @return p50值
     */
    public long getP50() {
        return getPercentile(0.5);
    }

    /**
     * 获取p90百分位
     *
     * @return p90值
     */
    public long getP90() {
        return getPercentile(0.9);
    }

    /**
     * 获取p99百分位
     *
     * @return p99值
     */
    public long getP99() {
        return getPercentile(0.99);
    }

    /**
     * 重置所有桶
     */
    public void reset() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i].reset();
        }
        count.reset();
        sum.reset();
        maxValue.set(0);
    }
}
