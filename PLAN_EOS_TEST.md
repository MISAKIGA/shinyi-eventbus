# EventBus EOS 测试用例设计方案

## 一、现有测试问题分析

### 1.1 KafkaEosTest.java 的核心问题

**问题 1：绕过 EventBus 框架**

```java
// KafkaEosTest.java 直接使用原生 API
KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);
ProducerRecord<String, byte[]> record = new ProducerRecord<>(testTopic, messageKey, messageValue);
```

- 直接使用 `KafkaProducer` 而不是 `EventListenerRegistryManager.publish()`
- 直接使用 `KafkaConsumer` 而不是 `@EventBusListener` 框架监听器
- 无法测试框架级别的 EOS 语义

**问题 2：同步阻塞模式导致挂死**

```java
// KafkaEosTest.java lines 174-176
for (Future<RecordMetadata> future : futures) {
    future.get(30, TimeUnit.SECONDS);  // 同步阻塞
}
```

- 所有 `.get()` 调用都是同步阻塞
- Latch 超时仅 2 分钟，导致测试挂死

## 二、EventBus EOS 框架核心机制

### 2.1 EOS 配置路径

| 配置位置 | 设置 | 用途 |
|----------|------|------|
| KafkaConnectConfig | enableIdempotence=true | 幂等生产者 (acks=all, retries=MAX) |
| KafkaConnectConfig | enableManualCommit=true | 手动 offset 提交 |
| KafkaConnectConfig | commitBatchSize=N | 批量提交大小 |
| @EventBusListener | exactlyOnce=true | 监听器级别 EOS 启用 |
| @EventBusListener | commitBatchSize=N | 监听器级别批量大小 |

### 2.2 框架事件流 (EOS 模式)

```
发布者:
EventListenerRegistryManager.publish(KAFKA, eventModel)
    → KafkaMqEventListenerRegistry.publish(eventModel)
    → KafkaProducer.send() with idempotence
    → Message written to Kafka

消费者:
KafkaConsumer.poll()
    → KafkaMqEventListenerRegistry.trackOffsetAndCommit()
    → OffsetCommitState batch accumulation
    → commitSync() when commitBatchSize reached
    → MethodEventListener.onMessage() triggers user listener
```

## 三、正确 EOS 测试架构

### 3.1 测试组件关系

```
EventBusEosTest
├── KafkaContainer (Testcontainers)
├── KafkaConnectConfig (EOS 配置)
├── EventListenerRegistryManager (框架核心)
├── KafkaMqEventListenerRegistry (Kafka 实现)
├── TestEventListener (实现 EventListener 接口)
│   └── Methods annotated with @EventBusListener
└── EventModel (测试事件)
```

### 3.2 关键测试原则

1. **通过框架 API 发布**: 必须使用 `registryManager.publish(EventBusType.KAFKA, eventModel)`
2. **通过框架监听器接收**: 必须实现 `EventListener` 接口或使用 `@EventBusListener` 注解
3. **异步非阻塞**: 使用 `CountDownLatch` + `EventCallback` 模式，避免 `.get()` 阻塞
4. **验证框架内 EOS**: 验证不丢失、不重复，而不是验证原生 Kafka API

## 四、详细测试用例设计

### 测试用例列表

| ID | 测试名称 | 目的 | 复杂度 |
|----|----------|------|--------|
| EOS-1 | testEosNoMessageLoss | 验证 EOS 模式发布+监听，消息不丢失 | M |
| EOS-2 | testEosNoMessageDuplication | 验证 EOS 模式重复发布不产生重复消费 | H |
| EOS-3 | testEosConsumerRestartResume | 验证 EOS 消费者重启后从上次偏移量恢复 | H |
| EOS-4 | testEosMultiPartitionConsumption | 验证 EOS 多分区消费正确性 | M |
| EOS-5 | testEosConcurrentPublishConsume | 验证 EOS 并发发布+消费语义 | H |
| EOS-6 | testEosVsNonEosComparison | 对比 EOS vs 非 EOS 模式差异 | M |
| EOS-7 | testEosManualCommitBatchSize | 验证不同 commitBatchSize 效果 | M |
| EOS-8 | testEosExactlyOnceAnnotation | 验证 @EventBusListener(exactlyOnce=true) | H |

### EOS-1: testEosNoMessageLoss

**目的**: 验证在 EventBus EOS 模式下，发布的消息能被子框架监听器完整接收，无消息丢失。

**步骤**:
1. 启动 KafkaContainer，创建 EOS 配置的 KafkaConnectConfig
2. 创建 EventListenerRegistryManager，注册 TestEventListener
3. 通过 `registryManager.publish()` 异步发送 100 条消息
4. 监听器使用 CountDownLatch 计数，latch.await(60s)
5. 验证接收消息数 = 发送消息数

**预期结果**:
- 接收 100 条消息
- 每条消息的 sequence 字段连续无断裂
- 数据完整性验证通过

### EOS-2: testEosNoMessageDuplication

**目的**: 验证在 EOS 模式下，即使生产者因超时重试发送相同消息，消费者也不会收到重复消息。

**关键代码**:
```java
@Test
void testEosNoMessageDuplication() throws Exception {
    KafkaConnectConfig config = createEosConfig(); // idempotence=true

    EventListenerRegistryManager registryManager = createRegistryManagerWithListener(config);
    registryManager.start();

    int uniqueMessageCount = 100;
    CountDownLatch latch = new CountDownLatch(uniqueMessageCount * 2);
    Set<String> seenIds = ConcurrentHashMap.newKeySet();

    // 使用相同 eventId 发布两次
    for (int i = 0; i < uniqueMessageCount; i++) {
        String eventId = String.valueOf(i);
        EosEvent event = EosEvent.create(i);

        // 发送两次，使用相同 eventId
        EventModel<EosEvent> em1 = EventModel.build(TOPIC, event, eventId, true, "EVENT", null);
        EventModel<EosEvent> em2 = EventModel.build(TOPIC, event, eventId, true, "EVENT", null);

        registryManager.publish(EventBusType.KAFKA, em1);
        registryManager.publish(EventBusType.KAFKA, em2);
    }

    Thread.sleep(5000);

    // 验证：只收到 uniqueMessageCount 条（不是 2x）
    assertEquals(uniqueMessageCount, seenIds.size(), "幂等生产者防止重复");
}
```

### EOS-3: testEosConsumerRestartResume

**目的**: 验证 EOS 消费者重启后能从上次提交的偏移量恢复，无丢失无重复。

**步骤**:
1. 发布 200 条消息到 topic
2. 启动第一个消费者，消费 100 条并手动提交
3. 关闭第一个消费者
4. 启动第二个消费者（相同 groupId），验证从 101 开始
5. 验证第二个消费者未收到重复

### EOS-5: testEosConcurrentPublishConsume

**目的**: 验证 EOS 模式下并发发布和消费的数据一致性。

**步骤**:
1. 启动 4 个并发生产者线程，每个发布 250 条消息
2. 1 个消费者线程消费全部 1000 条消息
3. 验证消息完整性和顺序

## 五、测试基础设施

### 5.1 EOS 配置创建方法

```java
private KafkaConnectConfig createEosConfig() {
    KafkaConnectConfig config = new KafkaConnectConfig();
    config.setBootstrapServers(bootstrapServers);
    config.setTopic(TOPIC);
    config.setGroupId("eos-test-group-" + System.currentTimeMillis());

    // EOS 生产者设置
    config.setAcks("all");
    config.setEnableIdempotence(true);      // 关键：启用幂等
    config.setRetries(Integer.MAX_VALUE);
    config.setMaxInFlightRequestsPerConnection(5);

    // EOS 消费者设置
    config.setEnableAutoCommit(false);      // 关键：禁用自动提交
    config.setEnableManualCommit(true);     // 关键：启用手动提交
    config.setCommitBatchSize(50);          // 每 50 条消息提交一次

    // 性能优化
    config.setBatchSize(65536);
    config.setLing

    config.setCompressionType("snappy");

    return config;
}
```

### 5.2 Registry Manager 创建方法

```java
private EventListenerRegistryManager createRegistryManagerWithListener(
        KafkaConnectConfig config) {

    GenericApplicationContext ctx = new GenericApplicationContext();

    // 创建 Kafka 注册器
    KafkaMqEventListenerRegistry<EventModel<?>> kafkaRegistry =
            new KafkaMqEventListenerRegistry<>(ctx, "kafka", config);
    kafkaRegistry.init();

    // 注册注册器 bean
    ctx.registerBean("kafkaEventListenerRegistry",
            EventListenerRegistry.class, () -> kafkaRegistry);

    // 创建并注册测试监听器
    TestEventListener testListener = new TestEventListener(receivedIds, latch);
    ctx.registerBean("testEventListener", EventListener.class, () -> testListener);

    // 注册管理器
    ctx.registerBean(EventListenerRegistryManager.class);
    ctx.refresh();

    return ctx.getBean(EventListenerRegistryManager.class);
}
```

## 六、注意事项

### 6.1 避免测试挂死

1. **使用 CountDownLatch 而不是 Future.get()**: latch.await(timeout) 不会无限阻塞
2. **设置合理的超时**: 60 秒对大多数集成测试足够
3. **不要依赖异步发布的返回值**: 使用 EventCallback 而不是同步 .get()

### 6.2 确保测试独立性

1. **每个测试使用独立 topic**: `topic + UUID` 避免测试污染
2. **每个测试使用独立 groupId**: 避免偏移量冲突
3. **BeforeAll/AfterAll 管理 Kafka 生命周期**: KafkaContainer 启动一次

### 6.3 框架 API vs 原生 API

**必须使用框架 API**:
- `registryManager.publish(EventBusType.KAFKA, eventModel)` 发布事件
- `EventListener.onMessage()` 或 `@EventBusListener` 接收事件
- `KafkaConnectConfig` 配置 EOS 参数

**禁止使用原生 API**:
- `new KafkaProducer<>()` 直接创建生产者
- `new KafkaConsumer<>()` 直接创建消费者
- `ProducerRecord<>` 直接构造记录

## 七、文件位置

新测试文件应创建在:
```
src/test/java/com/shinyi/eventbus/kafka/EventBusEosTest.java
```

参考现有测试文件:
- `src/test/java/com/shinyi/eventbus/kafka/KafkaEventBusIntegrationTest.java`
- `src/test/java/com/shinyi/eventbus/kafka/KafkaEventBusBenchmarkTest.java`

## 八、成功标准

- [ ] 所有 8 个 EOS 测试用例通过
- [ ] 测试使用 `EventListenerRegistryManager.publish()` 发布事件
- [ ] 测试使用 `EventListener` 接口或 `@EventBusListener` 注解接收事件
- [ ] 无同步阻塞调用 (`.get()`)
- [ ] 60 秒超时合理设置，无测试挂死
- [ ] 验证 EOS 语义：不丢失、不重复
