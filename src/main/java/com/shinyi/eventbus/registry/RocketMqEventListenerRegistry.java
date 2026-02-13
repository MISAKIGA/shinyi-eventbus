package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.aliyun.openservices.ons.api.impl.rocketmq.ONSConsumerAbstract;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.MessageListener;
import com.aliyun.openservices.ons.api.ONSFactory;
import com.aliyun.openservices.ons.api.PropertyKeyConst;
import com.aliyun.openservices.ons.api.impl.rocketmq.ProducerImpl;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.*;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.consumer.listener.*;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.exception.MQClientException;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.MQProducer;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendCallback;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.client.producer.SendResult;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.consumer.ConsumeFromWhere;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.Message;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageExt;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.message.MessageQueue;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.common.protocol.heartbeat.MessageModel;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.EventListener;
import com.shinyi.eventbus.config.rocketmq.RocketMqConnectConfig;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RockMq事件监听器注册器
 * @author MSGA
 * @param <T>
 */
@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class RocketMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;

    protected final String registryBeanName;

    protected final RocketMqConnectConfig rocketMqConnectConfig;

    protected final List<MQConsumer> mqConsumers = new ArrayList<>();

    protected MQProducer mqProducer = null;

    protected final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()+1);

    protected final Serializer serializer = new BaseSerializer();

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.ROCKETMQ;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        if(listener == null) { return; }
        CompletableFuture[] futures = listener.stream().map(l -> CompletableFuture.runAsync(() -> {
            // 两种情况需要注册到当前实例
            // 1.指定了实例，且当前实例与指定的实例一致
            // 2.没指定实例，且当前实例是默认事件中心
            if(CollectionUtil.isNotEmpty(l.registryBeanName()) && CollectionUtil.contains(l.registryBeanName(), registryBeanName)
                    || CollectionUtil.isEmpty(l.registryBeanName()) && rocketMqConnectConfig.getIsDefault()) {
                if("PUSH".equals(l.consumerMode())) {
                    initPushConsumer(l);
                } else {
                    initPullConsumer(l);
                }
            }
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    @Override
    public void publish(T eventModel) {
        // 解决按序、事务发送
        EventCallback eventCallback = eventModel.getEventCallback();
        EventResult eventResult = new EventResult();
        try {
            Message message = new Message();
            message.setBody(serializer.serialize(eventModel, eventModel.getSerializeType()));
            message.setTopic(eventModel.getTopic());
            String tags = StrUtil.isBlank(eventModel.getTags()) ? "*": eventModel.getTags();
            message.setTags(tags);

            if(eventModel.isEnableAsync()) {
                mqProducer.send(message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        if(null != eventCallback) {
                            eventResult.setMessageId(sendResult.getMsgId());
                            eventResult.setTopic(sendResult.getMessageQueue().getTopic());
                            eventResult.setSourceResult(sendResult);
                            eventCallback.onSuccess(eventResult);
                        }
                    }
                    @Override
                    public void onException(Throwable e) {
                        if(null != eventCallback) {
                            eventResult.setTopic(eventModel.getTopic());
                            eventCallback.onFailure(eventResult, e);
                        }
                    }
                });
            } else {
                SendResult sendResult = mqProducer.send(message);
                if(null != eventCallback) {
                    eventResult.setMessageId(sendResult.getMsgId());
                    eventResult.setTopic(sendResult.getMessageQueue().getTopic());
                    eventCallback.onSuccess(eventResult);
                }
            }
        } catch (Exception e) {
            log.warn("{} 推送消息异常 {}", getEventBusType().getTypeName(), e.getMessage());
            if(null != eventCallback) {
                eventCallback.onFailure(eventResult, e);
            } else {
                // 如果未实现回调，则抛出异常
                throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_ERROR, e.getMessage());
            }
        }
    }

    public void newDefaultProducer() {
        if(rocketMqConnectConfig.getProducerGroupId() == null || rocketMqConnectConfig.isSkipCreateProducer()) {
            log.warn("跳过producer创建 {}。 skip: {}", registryBeanName, rocketMqConnectConfig.isSkipCreateProducer());
            return;
        }
        DefaultMQProducer mqProducer;
        // 无认证兼容
        if(rocketMqConnectConfig.getAccessKey() == null && rocketMqConnectConfig.getSecretKey() == null) {
            mqProducer = new DefaultMQProducer(rocketMqConnectConfig.getProducerGroupId());
            mqProducer.setNamesrvAddr(rocketMqConnectConfig.getNamesrvAddr());
        } else {
            Properties properties = new Properties();
            // 设置TCP接入域名，进入消息队列RocketMQ版控制台实例详情页面的接入点区域查看。
            properties.put(PropertyKeyConst.NAMESRV_ADDR, rocketMqConnectConfig.getNamesrvAddr());
            // AccessKey ID，阿里云身份验证标识。
            properties.put(PropertyKeyConst.AccessKey, rocketMqConnectConfig.getAccessKey());
            // AccessKey Secret，阿里云身份验证密钥。
            properties.put(PropertyKeyConst.SecretKey, rocketMqConnectConfig.getSecretKey());

            ProducerImpl producer = (ProducerImpl) ONSFactory.createProducer(properties);
            mqProducer = producer.getDefaultMQProducer();
        }
        this.mqProducer = mqProducer;
        try {
            mqProducer.start();
        } catch (MQClientException e) {
            log.warn("{} 生产者启动异常：{}", mqProducer.getInstanceName(), e.getMessage(), e);
        }
    }

    private DefaultMQPushConsumer newPushConsumer(String groupId) {
        DefaultMQPushConsumer mqConsumer;
        if(rocketMqConnectConfig.getAccessKey() != null && rocketMqConnectConfig.getSecretKey() != null) {
            Properties properties = new Properties();
            // 设置TCP接入域名，进入消息队列RocketMQ版控制台实例详情页面的接入点区域查看。
            properties.put(PropertyKeyConst.NAMESRV_ADDR, rocketMqConnectConfig.getNamesrvAddr());
            // AccessKey ID，阿里云身份验证标识。
            properties.put(PropertyKeyConst.AccessKey, rocketMqConnectConfig.getAccessKey());
            // AccessKey Secret，阿里云身份验证密钥。
            properties.put(PropertyKeyConst.SecretKey, rocketMqConnectConfig.getSecretKey());
            properties.put(PropertyKeyConst.GROUP_ID, groupId);

            // 通过反射拿到 consumer 的 defaultMQPushConsumer 这个字段
            try {
                ONSConsumerAbstract consumer = (ONSConsumerAbstract) ONSFactory.createConsumer(properties);
                Field field = ONSConsumerAbstract.class.getDeclaredField("defaultMQPushConsumer");
                field.setAccessible(true);
                mqConsumer = (DefaultMQPushConsumer) field.get(consumer);
            } catch (Exception e) {
                throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_ERROR, "初始化消费者异常："+e.getMessage());
            }

        } else {
            mqConsumer = new DefaultMQPushConsumer(groupId);
            mqConsumer.setNamesrvAddr(rocketMqConnectConfig.getNamesrvAddr());
        }
        // 此处当作每个消费实例都是新的
        String instanceName = UUID.fastUUID().toString().replace("-", "");
        mqConsumer.setInstanceName(instanceName);
        mqConsumers.add(mqConsumer);
        log.info("create new push consumer, instance [{}]", instanceName);
        return mqConsumer;
    }

    private MessageListener getMessageListener(EventListener<T> listener) {
        MessageListener messageListener;
        // 注册消息监听器
        switch (rocketMqConnectConfig.getConsumeMode()) {
            case "orderly":
                messageListener = (MessageListenerOrderly) (messages, context) -> {
                    try {
                        handleMsg(messages, listener);
                    } catch (Exception e) {
                        log.warn("消费异常：{}" , e.getMessage(), e);
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                    return ConsumeOrderlyStatus.SUCCESS;
                };
                break;
            case "concurrently":
            default:
                messageListener = (MessageListenerConcurrently) (messages, context) -> {
                    try {
                        handleMsg(messages, listener);
                    } catch (Exception e) {
                        log.warn("消费异常：{}" , e.getMessage(), e);
                        // 处理失败，稍后重试
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                    // 消费成功
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                };
                break;
        }
        return messageListener;
    }

    private void setListener(DefaultMQPushConsumer mqConsumer, EventListener<T> listener) throws MQClientException {
        String topics = listener.topic();
        String tags = StrUtil.isBlank(listener.tags()) ? "*" : listener.tags().trim();

        Map<String, String> subscriptionTable = new HashMap<>();
        for (String topic : topics.split(",")) {
            subscriptionTable.put(topic.trim(), tags);
        }

        mqConsumer.setSubscription(subscriptionTable);
        mqConsumer.setMessageListener(getMessageListener(listener));
    }

    /**
     * 初始化推模式消费者
     *
     * @param listener 事件监听器
     */
    private void initPushConsumer(EventListener<T> listener) {
        try {
            String groupId = listener.group();
            DefaultMQPushConsumer mqConsumer = newPushConsumer(groupId);
            //String tags = StrUtil.isBlank(listener.tags()) ? "*": listener.tags();
            //mqConsumer.subscribe(listener.topic(), tags);
            mqConsumer.setPullBatchSize(rocketMqConnectConfig.getPullBatchSize());
            mqConsumer.setConsumeTimeout(rocketMqConnectConfig.getConsumerTimeoutMillis());
            mqConsumer.setMaxReconsumeTimes(rocketMqConnectConfig.getMaxReconsumeTimes());
            mqConsumer.setMaxBatchConsumeWaitTime(rocketMqConnectConfig.getMaxBatchConsumeWaitTime(), TimeUnit.MILLISECONDS);

            setListener(mqConsumer, listener);
            // 设置消息模型
            if("broadcasting".equals(rocketMqConnectConfig.getMessageModel())) {
                mqConsumer.setMessageModel(MessageModel.BROADCASTING);
            } else {
                mqConsumer.setMessageModel(MessageModel.CLUSTERING);
            }
            // 设置消费偏移量
            String offset = StringUtils.hasText(listener.offset()) ? listener.offset() : rocketMqConnectConfig.getOffset();
            switch (offset) {
                case "earliest":
                    mqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
                    break;
                case "latest":
                    mqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
                    break;
                case "timestamp":
                    mqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_TIMESTAMP);
                    mqConsumer.setConsumeTimestamp(String.valueOf(System.currentTimeMillis()));
                    break;
                case "none":
                    break;
            }
            // 启动消费者
            mqConsumer.start();
            log.info("PUSH消费者已启动: group={}, topic={}", groupId, listener.topic());
        } catch (MQClientException e) {
            throw new RuntimeException("推模式消费者启动失败", e);
        }
    }

    private DefaultMQPullConsumer newPullConsumer(EventListener<T> listener) {
        DefaultMQPullConsumer mqConsumer = new DefaultMQPullConsumer(listener.group());
        mqConsumer.setNamesrvAddr(rocketMqConnectConfig.getNamesrvAddr());
        // 此处当作每个消费实例都是新的
        String instanceName = UUID.fastUUID().toString().replace("-", "");
        mqConsumer.setInstanceName(instanceName);
        mqConsumer.setConsumerPullTimeoutMillis(rocketMqConnectConfig.getConsumerTimeoutMillis());
        mqConsumer.setMaxReconsumeTimes(rocketMqConnectConfig.getMaxReconsumeTimes());
        mqConsumers.add(mqConsumer);
        log.info("create new pull consumer, instance [{}]", instanceName);
        return mqConsumer;
    }


    protected EventModel<?> deserialize(MessageExt message, EventListener<T> listener) {
        EventModel<?> eventModel;
        try {
            eventModel = serializer.deserialize(message.getBody(), listener.serializeType(), listener.entityType());
            if("MSG".equals(listener.serializeType())) {
                eventModel = EventModel.build(listener.topic(), message);
            }
        } catch (Throwable e) {
            log.warn(registryBeanName + " msgId: "+message.getMsgId()+" 消息解析失败：" + new String(message.getBody()), e);
            // 返回空
            eventModel = EventModel.build(listener.topic(), null);
        }
        eventModel.setRawData(message.getBody());
        eventModel.setGroup(listener.group());
        eventModel.setDriveType(registryBeanName+"#"+getEventBusType().getTypeName());
        if(eventModel.getEventId() == null) {
            eventModel.setEventId(message.getMsgId());
        }
        if(eventModel.getTopic() == null) {
            eventModel.setTopic(listener.topic());
        }
        return eventModel;
    }

    protected void handleMsg(List<MessageExt> messages, EventListener<T> listener) {
        List<EventModel<?>> eventModels = new ArrayList<>();
        for (MessageExt message : messages) {
            if (message.getBody() == null || message.getBody().length == 0) {
                log.warn("消息体为空，跳过处理. msgId={}", message.getMsgId());
                continue;
            }
            EventModel<?> eventModel = deserialize(message, listener);
            eventModels.add(eventModel);
        }
        try {
            listener.onMessageBatch((List<T>) eventModels);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 初始化拉模式消费者
     *
     * @param listener 事件监听器
     */
    private void initPullConsumer(EventListener<T> listener) {
        try {
            DefaultMQPullConsumer mqConsumer = newPullConsumer(listener);
            String tags = StrUtil.isBlank(listener.tags()) ? "*": listener.tags();

            // 启动消费者
            mqConsumer.start();
            log.info("拉模式消费者已启动: group={}, topic={}}", listener.group(), listener.topic());

            // 获取订阅 Topic 的所有队列
            Set<MessageQueue> messageQueues = mqConsumer.fetchSubscribeMessageQueues(listener.topic());

            // 为每个队列创建一个定时任务
            for (MessageQueue queue : messageQueues) {
                log.info("开始消费队列: {}", queue);

                // 使用 AtomicInteger 实现原子化操作
                // 初始等待时间（秒）
                AtomicInteger initialDelay = new AtomicInteger(1);
                // 最大等待时间（秒）
                int maxDelay = 60;
                // 退避因子
                int backoffFactor = 2;

                // 提交定时任务
                scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        PullResult pullResult = mqConsumer.pull(queue, tags, getMessageQueueOffset(queue), 32); // 每次拉取 32 条消息
                        switch (pullResult.getPullStatus()) {
                            case FOUND: // 拉取到消息
                                List<MessageExt> messages = pullResult.getMsgFoundList();
                                handleMsg(messages, listener);
                                // 更新消费进度
                                putMessageQueueOffset(queue, pullResult.getNextBeginOffset());
                                break;
                            case NO_NEW_MSG: // 没有新消息
                                log.trace("没有新消息，稍后重试");
                                // 动态调整等待时间
                                int currentDelay = initialDelay.get();
                                int newDelay = Math.min(currentDelay * backoffFactor, maxDelay);
                                initialDelay.set(newDelay);
                                break;
                            case NO_MATCHED_MSG: // 没有匹配的消息
                                log.trace("没有匹配的消息");
                                break;
                            case OFFSET_ILLEGAL: // 消费进度非法
                                log.debug("消费进度非法");
                                break;
                            default:
                                break;
                        }
                    } catch (Exception e) {
                        log.warn("拉取消息失败: {}", e.getMessage(), e);
                    }
                    // 初始延迟和固定延迟
                }, initialDelay.get(), initialDelay.get(), TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException("拉模式消费者启动失败", e);
        }
    }


    // 用于存储每个队列的消费进度
    private final Map<MessageQueue, Long> offsetTable = new HashMap<>();

    /**
     * 获取队列的消费进度
     *
     * @param queue 消息队列
     * @return 消费进度
     */
    private long getMessageQueueOffset(MessageQueue queue) {
        return offsetTable.getOrDefault(queue, 0L);
    }

    /**
     * 更新队列的消费进度
     *
     * @param queue  消息队列
     * @param offset 消费进度
     */
    private void putMessageQueueOffset(MessageQueue queue, long offset) {
        offsetTable.put(queue, offset);
    }

    @Override
    public void close() throws Exception {
        //log.info("关闭 {} 事件监听器组件。", registryBeanName);
        if(mqProducer != null) {
            mqProducer.shutdown();
        }
        scheduler.shutdown();
        mqConsumers.forEach(m -> {
            if(m instanceof MQPullConsumer) {
                try {
                    ((MQPullConsumer) m).shutdown();
                } catch (Exception e) {
                    log.error("关闭 {} 事件监听器组件失败。", getEventBusType().getTypeName(), e);
                }
            } else if(m instanceof MQPushConsumer) {
                try {
                    ((MQPushConsumer) m).shutdown();
                } catch (Exception e) {
                    log.error("关闭 {} 事件监听器组件失败。", getEventBusType().getTypeName(), e);
                }
            }
        });
        log.info("{} 事件监听组件销毁完成。", registryBeanName);
    }
}
