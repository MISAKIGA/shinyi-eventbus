package com.shinyi.eventbus.listener;

import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.SerializeType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Performance benchmark for MethodEventListener parameter extraction.
 */
public class MethodEventListenerPerformanceTest {

    static final int WARMUP_ITERATIONS = 10_000;
    static final int BENCHMARK_ITERATIONS = 100_000;
    static final int BATCH_SIZE = 100;

    @Test
    public void benchmarkParameterExtraction() throws Exception {
        // Setup listener with List<String> parameter
        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<String> events) {
                // do nothing - just measure framework overhead
            }
        };
        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", String.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        // Prepare test messages
        List<EventModel<Object>> messages = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            EventModel<Object> em = EventModel.build("test-topic", "Message-" + i);
            em.setEventId(String.valueOf(i));
            messages.add(em);
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try {
                listener.handle(new ArrayList<>(messages));
            } catch (Throwable ignored) {}
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try {
                listener.handle(messages);
            } catch (Throwable ignored) {}
        }
        long end = System.nanoTime();

        double avgNanos = (end - start) / (double) BENCHMARK_ITERATIONS;
        double avgMicros = avgNanos / 1000;
        double throughput = 1_000_000_000.0 / avgNanos;

        System.out.println("\n=== MethodEventListener Parameter Extraction Benchmark ===");
        System.out.printf("Iterations: %,d%n", BENCHMARK_ITERATIONS);
        System.out.printf("Batch size: %d%n", BATCH_SIZE);
        System.out.printf("Total time: %.2f ms%n", (end - start) / 1_000_000.0);
        System.out.printf("Average per call: %.2f µs%n", avgMicros);
        System.out.printf("Throughput: %.2f calls/sec%n", throughput);
        System.out.printf("Messages/sec: %.2f M%n", (throughput * BATCH_SIZE) / 1_000_000);
    }

    @Test
    public void benchmarkDirectVsReflection() throws Exception {
        System.out.println("\n=== Direct Call vs Reflection + Extraction Benchmark ===");

        List<String> directResult = new ArrayList<>();
        List<EventModel<Object>> messages = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            EventModel<Object> em = EventModel.build("test-topic", "Message-" + i);
            em.setEventId(String.valueOf(i));
            messages.add(em);
        }

        // Direct list extraction (what extraction does)
        long start = System.nanoTime();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            directResult.clear();
            for (EventModel<Object> em : messages) {
                directResult.add((String) em.getEntity());
            }
        }
        long end = System.nanoTime();
        double directNanos = (end - start) / (double) WARMUP_ITERATIONS;

        System.out.printf("Direct list extraction: %.2f µs/call%n", directNanos / 1000);

        // Reflection-based extraction (what MethodEventListener does)
        Object listenerBean = new Object() {
            @SuppressWarnings("unused")
            public void onMessage(List<String> events) {}
        };
        Method method = listenerBean.getClass().getMethod("onMessage", List.class);

        MethodEventListener listener = new MethodEventListener(
                listenerBean, method, "test-topic", String.class,
                "test-group", "", "PUSH", new String[]{}, "test-app",
                SerializeType.RAW.getType(),
                "", "", "", "direct", "", true, false, false, 100, true
        );

        start = System.nanoTime();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try {
                listener.handle(messages);
            } catch (Throwable ignored) {}
        }
        end = System.nanoTime();
        double reflectionNanos = (end - start) / (double) WARMUP_ITERATIONS;

        System.out.printf("MethodEventListener.handle(): %.2f µs/call%n", reflectionNanos / 1000);
        System.out.printf("Overhead: %.2f µs/call (%.1f%%)%n",
                (reflectionNanos - directNanos) / 1000,
                ((reflectionNanos - directNanos) / reflectionNanos) * 100);
    }
}
