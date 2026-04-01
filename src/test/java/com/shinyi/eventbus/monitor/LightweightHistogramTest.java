package com.shinyi.eventbus.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LightweightHistogramTest {

    @Test
    public void testRecord() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(5);   // bucket 0
        histogram.record(15);  // bucket 1
        histogram.record(25);  // bucket 2

        assertEquals(3, histogram.getCount());
        assertEquals(15.0, histogram.getMean(), 0.01);
    }

    @Test
    public void testPercentile() {
        LightweightHistogram histogram = new LightweightHistogram();
        // 记录 100 个值，0-99
        for (int i = 0; i < 100; i++) {
            histogram.record(i);
        }

        // P50 应该接近 50
        assertTrue(histogram.getPercentile(0.5) >= 40);
        assertTrue(histogram.getPercentile(0.5) <= 60);

        // P99 应该接近 99
        assertTrue(histogram.getPercentile(0.99) >= 90);
    }

    @Test
    public void testReset() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(100);
        histogram.record(200);
        assertTrue(histogram.getCount() > 0);

        histogram.reset();

        assertEquals(0, histogram.getCount());
        assertEquals(0, histogram.getMean());
    }

    @Test
    public void testNegativeValueIgnored() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(-10);
        histogram.record(10);
        assertEquals(1, histogram.getCount());
    }

    @Test
    public void testMaxValue() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(10);
        histogram.record(50);
        histogram.record(30);
        assertEquals(50, histogram.getMaxValue());
    }

    @Test
    public void testP50() {
        LightweightHistogram histogram = new LightweightHistogram();
        for (int i = 0; i < 100; i++) {
            histogram.record(i);
        }
        long p50 = histogram.getP50();
        assertTrue(p50 >= 40 && p50 <= 60, "P50 should be around 50, got: " + p50);
    }

    @Test
    public void testP90() {
        LightweightHistogram histogram = new LightweightHistogram();
        for (int i = 0; i < 100; i++) {
            histogram.record(i);
        }
        long p90 = histogram.getP90();
        assertTrue(p90 >= 80 && p90 <= 100, "P90 should be around 90, got: " + p90);
    }

    @Test
    public void testP99() {
        LightweightHistogram histogram = new LightweightHistogram();
        for (int i = 0; i < 1000; i++) {
            histogram.record(i);
        }
        long p99 = histogram.getP99();
        assertTrue(p99 >= 900, "P99 should be around 990, got: " + p99);
    }

    @Test
    public void testMeanWithSingleValue() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(100);
        assertEquals(100.0, histogram.getMean(), 0.01);
    }

    @Test
    public void testMeanWithEmptyHistogram() {
        LightweightHistogram histogram = new LightweightHistogram();
        assertEquals(0.0, histogram.getMean(), 0.01);
    }

    @Test
    public void testPercentileWithEmptyHistogram() {
        LightweightHistogram histogram = new LightweightHistogram();
        assertEquals(0, histogram.getPercentile(0.5));
    }

    @Test
    public void testPercentileAtBoundary() {
        LightweightHistogram histogram = new LightweightHistogram();
        histogram.record(5);
        histogram.record(15);
        // P50 of 2 values should return a valid bucket value
        assertTrue(histogram.getPercentile(0.5) >= 0);
    }
}
