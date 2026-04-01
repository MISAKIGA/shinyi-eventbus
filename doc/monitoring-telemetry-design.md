# Monitoring & Telemetry Module Design

## 1. Overview

参考Flink和SeaTunnel的监控实现，为EventBus设计轻量、高性能、可扩展的监控遥测模块。

### Flink Metrics核心概念
- **MetricGroup**: 分层metric组织（group.name.subname）
- **Metric**: Counter、 Gauge、 Histogram、 Meter四种类型
- **Reporter**: 异步批量上报，支持多种输出（JMX、Prometheus、HTTP等）
- **MetricConfig**: Properties配置

### SeaTunnel Metrics核心概念
- **MetricsContext**: 指标上下文容器
- **MetricsCollector**: 定时收集器
- **JobMetrics**: 按job维度的指标聚合

## 2. Design Principles

1. **代码优雅轻量**: 贴合EventBus现有代码风格，用最少的代码实现
2. **高性能**: 完全异步非阻塞，不影响事件处理
3. **高稳定性**: 监控失败优雅降级，不影响主业务
4. **零依赖**: 不引入额外依赖

## 3. Module Structure

```
src/main/java/com/shinyi/eventbus/
├── monitor/
│   ├── Metrics.java                 # Metric接口（Counter, Histogram, Meter）
│   ├── NoOpMetrics.java             # 空实现（监控关闭时使用）
│   ├── SimpleMetrics.java           # 简单实现（默认开启）
│   ├── MetricsContext.java          # 指标上下文（按bus/topic组织）
│   ├── MetricsCollector.java        # 定时收集器（异步批量）
│   ├── MetricsReporter.java         # HTTP暴露接口
│   └── config/
│       └── MonitoringConfig.java    # 配置类
```

## 4. Metrics Data Model

### 4.1 Metric Types

```java
public interface Metrics {
    // 计数器 - 事件总数
    void incCounter(String name, long count);
    long getCounter(String name);

    // 瞬时值 - 当前队列深度等
    void recordGauge(String name, long value);

    // 直方图 - 延迟分布
    void recordHistogram(String name, long value);
    long getHistogramMean(String name);

    // 计量器 - 吞吐率
    void markMeter(String name, long count);
    double getMeterRate(String name);  // events/second
}
```

### 4.2 Metrics Collected

| Metric | Type | Description |
|--------|------|-------------|
| `events.total` | Counter | 总事件数 |
| `events.published` | Counter | 发布事件数 |
| `events.consumed` | Counter | 消费事件数 |
| `events.failed` | Counter | 失败事件数 |
| `latency.p50` | Histogram | P50延迟 |
| `latency.p95` | Histogram | P95延迟 |
| `latency.p99` | Histogram | P99延迟 |
| `latency.mean` | Histogram | 平均延迟 |
| `throughput` | Meter | 吞吐量(events/sec) |

### 4.3 Dimensions

- **bus**: guava, spring, rabbitmq, rocketmq, kafka, redis
- **topic**: 事件topic
- **listener**: 监听器方法名

## 5. Async Collection Mechanism

### 5.1 Architecture

```
EventHandler --[async]--> RingBuffer --[batch]--> ScheduledReporter --[HTTP]--> /metrics
     |                         |
     v                         v
 MetricsContext          MetricsCollector
 (ConcurrentHashMap)     (1min scheduled)
```

### 5.2 Implementation

**RingBuffer**: 使用Disruptor风格的环形缓冲区，无锁队列

**MetricsCollector**: 每60秒触发一次（可配置），批量聚合指标：
- 计算平均值、百分位数
- 重置增量计数器
- 更新滑动窗口

**设计要点**:
- 指标记录只在当前线程执行，`volatile`保证可见性
- 收集线程与记录线程完全隔离
- 聚合计算在收集线程完成，不阻塞事件处理

## 6. HTTP Endpoint

### 6.1 Spring Web Integration

```java
@ConditionalOnClass({WebMvcConfigurer.class})
@ConditionalOnProperty(name = "shinyi.eventbus.monitoring.http.enabled", havingValue = "true")
public class MonitoringAutoConfiguration {
    // 自动配置HTTP端点
}
```

### 6.2 Non-Spring Environment

使用轻量内嵌HTTP服务器（Netty或简单Socket），避免引入Servlet容器依赖。

### 6.3 Endpoint Design

```
GET /actuator/metrics/eventbus
Response:
{
  "timestamp": 1711872000000,
  "metrics": {
    "kafka": {
      "topic:order-created": {
        "events.total": 100000,
        "events.failed": 5,
        "latency.mean": 2.5,
        "latency.p95": 10.0,
        "throughput": 1500.0
      }
    },
    "guava": { ... }
  }
}
```

## 7. Configuration

```yaml
shinyi:
  eventbus:
    monitoring:
      enabled: false                    # 默认关闭
      interval-seconds: 60              # 收集间隔
      http:
        enabled: false                  # HTTP暴露默认关闭
        port: 8080                      # 默认端口
        path: /metrics/eventbus          # 默认路径
      metrics:
        - events.total
        - events.failed
        - latency.mean
        - latency.p95
        - throughput
```

## 8. Implementation Classes

### 8.1 Metrics接口

```java
public interface Metrics {
    void increment(String bus, String topic, String name, long delta);
    void record(String bus, String topic, String name, long value);
    Map<String, Object> collect();  // 收集所有指标
    void reset();                    // 重置增量值
}
```

### 8.2 NoOpMetrics (禁用时)

```java
public class NoOpMetrics implements Metrics {
    public static final NoOpMetrics INSTANCE = new NoOpMetrics();
    @Override public void increment(...) { }
    @Override public void record(...) { }
    @Override public Map<String, Object> collect() { return Collections.emptyMap(); }
    @Override public void reset() { }
}
```

### 8.3 SimpleMetrics (启用时)

使用`ConcurrentHashMap`存储，按bus/topic/histogram-name三级索引。

```java
public class SimpleMetrics implements Metrics {
    private final ConcurrentHashMap<String, Long> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> histogramSums = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> histogramCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> histogramValues = new ConcurrentHashMap<>();

    @Override
    public void increment(String bus, String topic, String name, long delta) {
        String key = key(bus, topic, name);
        counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
    }

    @Override
    public void record(String bus, String topic, String name, long value) {
        String key = key(bus, topic, name);
        gauges.put(key, value);
        histogramSums.computeIfAbsent(key, k -> new LongAdder()).add(value);
        histogramCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        // 更新滑动窗口百分位
        updateHistogram(key, value);
    }

    @Override
    public Map<String, Object> collect() {
        // 聚合输出JSON友好的格式
    }

    @Override
    public void reset() {
        // 重置增量值（但保留累计值用于计算rate）
    }
}
```

### 8.4 MetricsCollector (定时任务)

```java
public class MetricsCollector implements Runnable {
    private final Metrics metrics;
    private final long intervalMs;
    private volatile boolean running = true;

    @Override
    public void run() {
        if (!running) return;
        Map<String, Object> snapshot = metrics.collect();
        // 异步发送到Reporter
    }
}
```

### 8.5 MetricsReporter (HTTP)

```java
@RestController
@RequestMapping("${shinyi.eventbus.monitoring.http.path:/metrics/eventbus}")
public class MetricsReporter {
    private final Metrics metrics;

    @GetMapping
    public Map<String, Object> metrics() {
        return metrics.collect();
    }
}
```

## 9. Integration Points

### 9.1 EventListenerRegistry

在发布和消费事件时自动记录指标：

```java
// 在 AbstractEventListenerRegistry 或每个具体实现中
private final Metrics metrics;

public void publish(EventModel<?> event) {
    long start = System.currentTimeMillis();
    try {
        doPublish(event);
        metrics.increment(event.getBusType(), event.getTopic(), "events.published", 1);
    } catch (Exception e) {
        metrics.increment(event.getBusType(), event.getTopic(), "events.failed", 1);
        throw e;
    } finally {
        long latency = System.currentTimeMillis() - start;
        metrics.record(event.getBusType(), event.getTopic(), "latency", latency);
    }
}
```

### 9.2 Auto Configuration

```java
@Configuration
@ConditionalOnProperty(name = "shinyi.eventbus.monitoring.enabled", havingValue = "true")
public class MonitoringAutoConfiguration {

    @Bean
    public Metrics eventbusMetrics() {
        return new SimpleMetrics();
    }

    @Bean
    public MetricsCollector metricsCollector(Metrics metrics,
            @Value("${shinyi.eventbus.monitoring.interval-seconds:60}") long interval) {
        return new MetricsCollector(metrics, interval * 1000);
    }

    @Bean
    public ScheduledFuture<?> metricsScheduledTask(MetricsCollector collector) {
        return Executors.newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(collector, interval, interval, TimeUnit.MILLISECONDS);
    }
}
```

## 10. Performance Considerations

1. **Thread-Local记录**: 避免锁竞争，用ThreadLocal缓存当前线程的指标
2. **批量聚合**: 收集器批量计算，减少计算频率
3. **无锁数据结构**: 使用`LongAdder`替代`AtomicLong`减少CAS竞争
4. **读写分离**: 指标记录(写)和收集(读)分离，CopyOnWriteArrayList存储收集时的快照
5. **优雅降级**: 如果监控模块出错，捕获异常继续业务逻辑

## 11. Graceful Degradation

```java
try {
    metrics.increment(...);
} catch (Throwable t) {
    // 日志记录但不抛出，避免影响业务
    logger.debug("Failed to record metrics", t);
}
```

## 12. Summary

| Feature | Implementation |
|---------|---------------|
| Metric Types | Counter, Gauge, Histogram, Meter |
| Storage | ConcurrentHashMap + LongAdder |
| Collection | ScheduledExecutorService, 60s interval |
| HTTP | Spring MVC or Netty |
| Default State | Disabled |
| Dependencies | None (zero extra deps) |
