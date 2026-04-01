# Metrics System Bug Fix & Enhancement Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the metrics race condition bug where HTTP API returns zeros, and enhance the metrics system with cumulative tracking and configurable reset strategies.

**Architecture:**
- Fix race condition by reversing reset/collect order and using atomic operations with ReadWriteLock
- Add dual metrics model: delta (periodic, resettable) + cumulative (never reset, for billing/planning)
- Implement configurable reset strategies: NEVER, DAILY, HOURLY, INTERVAL, MANUAL
- Enhance HTTP API response to include both period and cumulative data

**Tech Stack:** Java 8+, Spring Boot, ConcurrentHashMap, ReentrantReadWriteLock, LightweightHistogram

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/main/java/com/shinyi/eventbus/monitor/ResetStrategy.java` | **CREATE** - Enum for reset strategies |
| `src/main/java/com/shinyi/eventbus/monitor/SimpleMetrics.java` | Add cumulative histograms, `collectAndReset()` method, ReadWriteLock |
| `src/main/java/com/shinyi/eventbus/monitor/MetricsCollector.java` | Use atomic `collectAndReset()`, add scheduled reset logic |
| `src/main/java/com/shinyi/eventbus/monitor/MetricsSnapshot.java` | Add `cumulative` section, `period` metadata |
| `src/main/java/com/shinyi/eventbus/monitor/config/MonitoringConfig.java` | Add reset strategy config |
| `src/main/java/com/shinyi/eventbus/monitor/LightweightHistogram.java` | Add `reset()` method for histograms |
| `src/test/java/com/shinyi/eventbus/monitor/SimpleMetricsTest.java` | Add tests for new behavior |

---

## Task 1: Create ResetStrategy Enum

**Files:**
- Create: `src/main/java/com/shinyi/eventbus/monitor/ResetStrategy.java`

- [ ] **Step 1: Write the enum**

```java
package com.shinyi.eventbus.monitor;

/**
 * Reset strategy for metrics
 */
public enum ResetStrategy {
    /**
     * Never reset - cumulative metrics only
     */
    NEVER,

    /**
     * Reset at midnight every day
     */
    DAILY,

    /**
     * Reset at the top of every hour
     */
    HOURLY,

    /**
     * Reset at a fixed interval (configurable via resetIntervalSeconds)
     */
    INTERVAL,

    /**
     * Only reset when explicitly called (manual trigger)
     */
    MANUAL
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/ResetStrategy.java
git commit -m "feat(monitor): add ResetStrategy enum for configurable metrics reset"
```

---

## Task 2: Add Reset Method to LightweightHistogram

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/LightweightHistogram.java`

- [ ] **Step 1: Read the file to understand structure**

```java
// Read LightweightHistogram.java
```

- [ ] **Step 2: Add reset() method**

Add a `reset()` method after the existing histogram data fields. The method should reset all internal counters to zero.

- [ ] **Step 3: Add test for reset**

In `src/test/java/com/shinyi/eventbus/monitor/LightweightHistogramTest.java`, add:

```java
@Test
public void testReset() {
    histogram.record(100);
    histogram.record(200);
    assertTrue(histogram.getCount() > 0);

    histogram.reset();

    assertEquals(0, histogram.getCount());
    assertEquals(0, histogram.getMean());
}
```

- [ ] **Step 4: Run test**

```bash
mvn test -DskipTests=false -Dtest=LightweightHistogramTest -q
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/LightweightHistogram.java src/test/java/com/shinyi/eventbus/monitor/LightweightHistogramTest.java
git commit -m "feat(monitor): add reset() method to LightweightHistogram"
```

---

## Task 3: Enhance SimpleMetrics with Atomic Operations and Cumulative Tracking

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/SimpleMetrics.java`

- [ ] **Step 1: Read current implementation**

```java
// Read SimpleMetrics.java
```

- [ ] **Step 2: Add imports and fields**

Add:
```java
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
```

Add new fields:
```java
private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
private final ConcurrentHashMap<String, LightweightHistogram> cumulativeHistograms = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, AtomicLong> cumulativeLatencySum = new ConcurrentHashMap<>();
```

- [ ] **Step 3: Modify increment() to use read lock**

Wrap the increment logic in `rwLock.readLock().lock()` / `unlock()`.

- [ ] **Step 4: Modify recordLatency() to use read lock and accumulate to cumulative**

```java
public void recordLatency(String bus, String topic, long latencyMs) {
    rwLock.readLock().lock();
    try {
        String key = key(bus, topic, "latency");
        histograms.computeIfAbsent(key, k -> new LightweightHistogram()).record(latencyMs);

        // Accumulate to cumulative
        cumulativeLatencySum.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(latencyMs);
    } finally {
        rwLock.readLock().unlock();
    }
}
```

- [ ] **Step 5: Add collectAndReset() method**

```java
/**
 * Atomic collect and reset operation
 * @return snapshot with current period data
 */
public MetricsSnapshot collectAndReset() {
    rwLock.writeLock().lock();
    try {
        long timestamp = System.currentTimeMillis();

        // Collect period data
        Map<String, Long> countersSnapshot = new HashMap<>();
        counters.forEach((k, v) -> countersSnapshot.put(k, v.sumThenReset()));

        Map<String, MetricsSnapshot.HistogramData> histogramsSnapshot = new HashMap<>();
        histograms.forEach((k, h) -> {
            MetricsSnapshot.HistogramData data = new MetricsSnapshot.HistogramData(
                    h.getCount(),
                    h.getMean(),
                    h.getP50(),
                    h.getP90(),
                    h.getP99()
            );
            h.reset();
            histogramsSnapshot.put(k, data);
        });

        Map<String, AtomicLong> gaugesSnapshot = new HashMap<>();
        gauges.forEach((k, v) -> gaugesSnapshot.put(k, new AtomicLong(v.get())));

        // Build snapshot with cumulative data
        Map<String, Long> cumulativeCountersSnapshot = new HashMap<>();
        totalCounters.forEach((k, v) -> cumulativeCountersSnapshot.put(k, v.get()));

        Map<String, MetricsSnapshot.HistogramData> cumulativeHistogramsSnapshot = new HashMap<>();
        cumulativeHistograms.forEach((k, v) -> {
            long count = cumulativeLatencyCounters.get(k).get();
            cumulativeHistogramsSnapshot.put(k, new MetricsSnapshot.HistogramData(
                    count,
                    count > 0 ? cumulativeLatencySum.get(k).get() / (double) count : 0,
                    0, 0, 0
            ));
        });

        return new MetricsSnapshot(timestamp, countersSnapshot, gaugesSnapshot, histogramsSnapshot,
                cumulativeCountersSnapshot, cumulativeHistogramsSnapshot);
    } finally {
        rwLock.writeLock().unlock();
    }
}
```

- [ ] **Step 6: Update MetricsSnapshot constructor call in collect()**

The existing `collect()` method should continue to work without resetting (for read-only access).

- [ ] **Step 7: Add test**

```java
@Test
public void testAtomicCollectAndReset() throws Exception {
    metrics.increment("kafka", "topic1", "events.consumed", 100);
    metrics.recordLatency("kafka", "topic1", 50);

    // First collect - should have data
    MetricsSnapshot snap1 = ((SimpleMetrics) metrics).collectAndReset();
    assertEquals(100L, snap1.getCounters().get("kafka:topic1:events.consumed"));

    // After reset - counters should be zero
    MetricsSnapshot snap2 = metrics.collect();
    assertEquals(0L, snap2.getCounters().get("kafka:topic1:events.consumed"));

    // Cumulative should still have data
    assertEquals(100L, snap1.getCumulativeCounters().get("kafka:topic1:events.consumed"));
}
```

- [ ] **Step 8: Run tests**

```bash
mvn test -DskipTests=false -Dtest=SimpleMetricsTest -q
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/SimpleMetrics.java src/test/java/com/shinyi/eventbus/monitor/SimpleMetricsTest.java
git commit -m "feat(monitor): add atomic collectAndReset with cumulative tracking"
```

---

## Task 4: Update MetricsSnapshot to Support Cumulative Data

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/MetricsSnapshot.java`

- [ ] **Step 1: Read current MetricsSnapshot**

```java
// Read MetricsSnapshot.java
```

- [ ] **Step 2: Add cumulative fields and new constructor**

```java
private final Map<String, Long> cumulativeCounters;
private final Map<String, HistogramData> cumulativeHistograms;

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

// Add getters
public Map<String, Long> getCumulativeCounters() { return cumulativeCounters; }
public Map<String, HistogramData> getCumulativeHistograms() { return cumulativeHistograms; }
```

- [ ] **Step 3: Run compile check**

```bash
mvn compile -q 2>&1 | head -50
```

Fix any compilation errors (likely need to update the existing constructor or add a default for backward compatibility).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/MetricsSnapshot.java
git commit -m "feat(monitor): add cumulative metrics to MetricsSnapshot"
```

---

## Task 5: Update MonitoringConfig with Reset Strategy

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/config/MonitoringConfig.java`

- [ ] **Step 1: Read current config**

```java
// Read MonitoringConfig.java
```

- [ ] **Step 2: Add reset strategy fields**

```java
private ResetStrategy resetStrategy = ResetStrategy.INTERVAL;
private long resetIntervalSeconds = 86400; // 24 hours
private String dailyResetTime = "00:00";
```

- [ ] **Step 3: Run compile check**

```bash
mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/config/MonitoringConfig.java
git commit -m "feat(monitor): add reset strategy config to MonitoringConfig"
```

---

## Task 6: Update MetricsCollector with Atomic Operations and Scheduled Reset

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/MetricsCollector.java`

- [ ] **Step 1: Read current implementation**

```java
// Read MetricsCollector.java
```

- [ ] **Step 2: Add fields for reset tracking**

```java
private final ResetStrategy resetStrategy;
private final long resetIntervalMs;
private volatile long lastResetTime = System.currentTimeMillis();
```

- [ ] **Step 3: Update constructor**

```java
public MetricsCollector(Metrics metrics, long intervalMs, boolean logEnabled,
                       ResetStrategy resetStrategy, long resetIntervalMs) {
    this.metrics = metrics;
    this.intervalMs = intervalMs;
    this.logEnabled = logEnabled;
    this.resetStrategy = resetStrategy;
    this.resetIntervalMs = resetIntervalMs;
    this.lastResetTime = System.currentTimeMillis();
}
```

- [ ] **Step 4: Add shouldReset() method**

```java
private boolean shouldReset() {
    if (resetStrategy == ResetStrategy.NEVER) {
        return false;
    }
    if (resetStrategy == ResetStrategy.MANUAL) {
        return false; // Only reset when explicitly called
    }

    long now = System.currentTimeMillis();
    long elapsed = now - lastResetTime;

    switch (resetStrategy) {
        case DAILY:
            // Check if we've passed midnight since last reset
            return hasDayPassed(lastResetTime, now);
        case HOURLY:
            return elapsed >= 3600000; // 1 hour
        case INTERVAL:
        default:
            return elapsed >= resetIntervalMs;
    }
}

private boolean hasDayPassed(long lastReset, long now) {
    // Simple check: if elapsed > 23 hours, assume day passed
    // In production, compare calendar days
    return (now - lastReset) > 23 * 3600000;
}
```

- [ ] **Step 5: Update run() to use atomic collectAndReset**

```java
@Override
public void run() {
    if (!running) return;
    try {
        long now = System.currentTimeMillis();

        // Check if we should reset
        if (shouldReset() && metrics instanceof SimpleMetrics) {
            ((SimpleMetrics) metrics).collectAndReset();
            lastResetTime = now;
        }

        // Collect without reset (we already did atomic reset if needed)
        lastSnapshot = metrics.collect();
        lastCollectTime = now;

        if (logEnabled && log.isInfoEnabled()) {
            printMetricsLog(now);
        }
    } catch (Throwable t) {
        log.warn("Failed to collect metrics", t);
    }
}
```

- [ ] **Step 6: Add manual reset method**

```java
public void reset() {
    if (metrics instanceof SimpleMetrics) {
        ((SimpleMetrics) metrics).collectAndReset();
        lastResetTime = System.currentTimeMillis();
    }
}
```

- [ ] **Step 7: Update MonitoringAutoConfiguration to pass new config**

```java
// In MonitoringAutoConfiguration.java
@Bean
public MetricsCollector metricsCollector(Metrics metrics) {
    MetricsHolder.setMetrics(metrics);
    return new MetricsCollector(metrics,
            config.getIntervalSeconds() * 1000,
            config.getLog().isEnabled(),
            config.getResetStrategy(),
            config.getResetIntervalSeconds() * 1000);
}
```

- [ ] **Step 8: Run compile check**

```bash
mvn compile -q
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/MetricsCollector.java src/main/java/com/shinyi/eventbus/monitor/config/MonitoringAutoConfiguration.java
git commit -m "feat(monitor): use atomic collectAndReset with scheduled reset"
```

---

## Task 7: Update MetricsEndpoint Response Format

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/config/MetricsEndpoint.java`

- [ ] **Step 1: Read current implementation**

```java
// Read MetricsEndpoint.java
```

The endpoint already returns `MetricsSnapshot` which now has cumulative data. No changes needed if `MetricsSnapshot.toString()` or JSON serialization handles the new fields.

- [ ] **Step 2: Verify JSON serialization works**

The `MetricsSnapshot` uses Map fields which Jackson/Spring will serialize automatically. The response will include:
- `timestamp`
- `counters` (period)
- `gauges`
- `histograms` (period)
- `cumulativeCounters` (new)
- `cumulativeHistograms` (new)

- [ ] **Step 3: No code changes needed**

If the existing serialization works, skip to commit. If not, add a `@JsonIgnore` or custom serializer as needed.

- [ ] **Step 4: Commit (if changes made)**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/config/MetricsEndpoint.java
git commit -m "feat(monitor): ensure MetricsEndpoint serializes cumulative data"
```

---

## Task 8: Update YAML Configuration Documentation

**Files:**
- Modify: `src/main/java/com/shinyi/eventbus/monitor/config/MonitoringConfig.java` (add Javadoc)

- [ ] **Step 1: Add configuration Javadoc**

Document the new config fields:

```java
/**
 * Reset strategy for metrics:
 * - NEVER: Never reset, cumulative only
 * - DAILY: Reset at midnight each day
 * - HOURLY: Reset at top of each hour
 * - INTERVAL: Reset every resetIntervalSeconds (default 24h)
 * - MANUAL: Only reset when explicitly called
 */
private ResetStrategy resetStrategy = ResetStrategy.INTERVAL;

/**
 * Interval in seconds for INTERVAL reset strategy
 */
private long resetIntervalSeconds = 86400;

/**
 * Time of day for DAILY reset in HH:mm format
 */
private String dailyResetTime = "00:00";
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/shinyi/eventbus/monitor/config/MonitoringConfig.java
git commit -m "docs: document reset strategy config options"
```

---

## Task 9: End-to-End Verification

**Files:**
- No file changes - verification only

- [ ] **Step 1: Build and test**

```bash
mvn clean test -DskipTests=false -q
```

- [ ] **Step 2: Run application and verify**

Start the demo application and:
1. Publish some messages
2. Check HTTP endpoint returns correct counters
3. Check logs show same values
4. Wait for reset interval and verify cumulative persists

- [ ] **Step 3: Verify configuration**

Test different `resetStrategy` values in application.yml.

---

## Summary

| Task | Description | Risk |
|------|-------------|------|
| 1 | Create ResetStrategy enum | Low |
| 2 | Add reset() to LightweightHistogram | Low |
| 3 | Enhance SimpleMetrics with atomic ops + cumulative | **High** - core change |
| 4 | Update MetricsSnapshot | Medium |
| 5 | Update MonitoringConfig | Low |
| 6 | Update MetricsCollector | **High** - changes scheduling |
| 7 | Update MetricsEndpoint | Low |
| 8 | Document config | Low |
| 9 | End-to-end verification | - |

**Critical Path:** Tasks 3 and 6 are the most complex and should be tested thoroughly.
