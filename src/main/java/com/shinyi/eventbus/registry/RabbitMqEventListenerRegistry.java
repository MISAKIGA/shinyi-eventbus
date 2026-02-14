package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.rabbitmq.client.*;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.rabbit.AdvancedRabbitMqAsyncSender;
import com.shinyi.eventbus.config.rabbit.RabbitMqConnectConfig;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * RockMq事件监听器注册器
 * @author MSGA
 */
@Slf4j
@RequiredArgsConstructor
public class RabbitMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final RabbitMqConnectConfig rabbitMqConnectConfig;

    private Connection producerConnection;
    private Connection consumerConnection;
    private Channel producerChannel;
    private Channel producerAsyncChannel;
    protected final Set<Channel> consumerChannels = new ConcurrentHashSet<>();
    protected final Serializer serializer = new BaseSerializer();
    protected AdvancedRabbitMqAsyncSender asyncSender;

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.RABBITMQ;
    }

    public void init() throws IOException, TimeoutException {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitMqConnectConfig.getHost());
            factory.setPort(rabbitMqConnectConfig.getPort());
            factory.setUsername(rabbitMqConnectConfig.getUsername());
            factory.setPassword(rabbitMqConnectConfig.getPassword());
            factory.setVirtualHost(rabbitMqConnectConfig.getVirtualHost());
            producerConnection = factory.newConnection();
            consumerConnection = factory.newConnection();
            producerChannel = producerConnection.createChannel();
            producerAsyncChannel = producerConnection.createChannel();
            asyncSender = new AdvancedRabbitMqAsyncSender(producerAsyncChannel);
        } catch (Exception e) {
            log.error("RabbitMQ初始化失败：{}", e.getMessage(), e);
            throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_ERROR, "RabbitMQ初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        if(listener == null) { return; }
        CompletableFuture[] futures = listener.stream().map(l -> CompletableFuture.runAsync(() -> {
            // 两种情况需要注册到当前实例
            // 1.指定了实例，且当前实例与指定的实例一致
            // 2.没指定实例，且当前实例是默认事件中心
            if(CollectionUtil.isNotEmpty(l.registryBeanName()) && CollectionUtil.contains(l.registryBeanName(), registryBeanName)
                    || CollectionUtil.isEmpty(l.registryBeanName()) && rabbitMqConnectConfig.getIsDefault()) {
                initConsumer(l);
            }
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    private Channel getConsumerChannel(EventListener<T> listener, String queueName, String exchange,
                                       String routingKey) throws IOException {
        if(consumerConnection == null) {
            throw new RuntimeException("创建rabbitmq消费者失败，没有初始化连接。请检查 " + rabbitMqConnectConfig.getHost() + ":" + rabbitMqConnectConfig.getPort());
        }
        Channel consumerChannel = consumerConnection.createChannel();
        // 声明交换机和队列
        consumerChannel.exchangeDeclare(
                exchange,
                listener.exchangeType(),
                listener.isDurable(),
                listener.autoDelete(),
                null
        );
        consumerChannel.queueDeclare(
                queueName,
                listener.isDurable(),
                false,
                listener.autoDelete(),
                null
        );
        consumerChannel.queueBind(
                queueName,
                exchange,
                routingKey
        );
        consumerChannels.add(consumerChannel);
        return consumerChannel;
    }

    protected EventModel<?> deserialize(byte[] body, String consumerTag, EventListener<T> listener) {
        EventModel<?> eventModel;
        try {
            eventModel = serializer.deserialize(body, listener.serializeType(), listener.entityType());
            if("MSG".equals(listener.serializeType())) {
                eventModel = EventModel.build(listener.topic(), null);
            }
        } catch (Throwable e) {
            log.warn(registryBeanName + " msgId: "+consumerTag+" 消息反序列化失败：" + new String(body));
            // 返回空
            eventModel = EventModel.build(listener.topic(), null);
        }
        eventModel.setRawData(body);
        eventModel.setGroup(listener.group());
        eventModel.setDriveType(registryBeanName+"#"+getEventBusType().getTypeName());
        if(eventModel.getEventId() == null) {
            eventModel.setEventId(consumerTag);
        }
        if(eventModel.getTopic() == null) {
            eventModel.setTopic(listener.topic());
        }
        return eventModel;
    }

    private void initConsumer(EventListener<T> listener) {
        try {
            // 生成基于消费者组的队列名
            String queueName;
            String routingKey;
            String exchange;
            if(StringUtils.hasText(listener.queue())) {
                // queue 不为空则使用 rabbitmq 语义
                queueName = listener.queue();
                routingKey = listener.routingKey();
                exchange = listener.exchange();

                if(StringUtils.hasText(routingKey)) {
                    routingKey = exchange;
                }
            } else {
                // queue 为空则使用兼容性语义
                // queue = group, routingKey = tags, exchange = topic
                queueName = listener.group();
                routingKey = listener.tags();
                exchange = listener.topic();

                if(!StringUtils.hasText(routingKey)) {
                    routingKey = exchange;
                }
                log.debug("使用兼容性RabbitMQ语义。");
            }
            Channel consumerChannel = getConsumerChannel(listener, queueName, exchange, routingKey);
            DefaultConsumer defaultConsumer = new DefaultConsumer(consumerChannel) {
                @Override
                public void handleDelivery(
                        String consumerTag,
                        Envelope envelope,
                        AMQP.BasicProperties properties,
                        byte[] body
                ) throws IOException {
                    try {
                        if (body == null || body.length == 0) {
                            log.warn("消息体为空，跳过处理. consumerTag={}", consumerTag);
                            return;
                        }
                        EventModel<?> eventModel = deserialize(body, consumerTag, listener);
                        listener.onMessage((T) eventModel);
                        consumerChannel.basicAck(envelope.getDeliveryTag(), false);
                    } catch (Exception e) {
                        log.warn("消息处理失败: " + e.getMessage(), e);
                    }
                }
            };
            String consumerTag = consumerChannel.basicConsume(queueName, false, defaultConsumer);
            log.info("创建Rabbit消费者 consumerTag: {}, queue: {}, routingKey: {} exchange: {}",
                    consumerTag, queueName, exchange, routingKey);
        } catch (Exception e) {
            throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_ERROR, e.getMessage(), e);
        }
    }

    private void sendAsync(T eventModel, String exchange, String routingKey, byte[] body) throws IOException {
        // 启用 Confirm 模式
        producerAsyncChannel.confirmSelect();
        long seqNo = producerChannel.getNextPublishSeqNo();
        EventCallback eventCallback = eventModel.getEventCallback();
        // 添加确认监听器
        producerAsyncChannel.addConfirmListener(new ConfirmListener() {
            @Override
            public void handleAck(long deliveryTag, boolean multiple) {
                handleConfirm(deliveryTag, multiple, true);
            }
            @Override
            public void handleNack(long deliveryTag, boolean multiple) {
                handleConfirm(deliveryTag, multiple, false);
            }
            private void handleConfirm(long deliveryTag, boolean multiple, boolean ack) {
                EventResult eventResult = new EventResult();
                eventResult.setMessageId(String.valueOf(seqNo));
                eventResult.setTopic(eventModel.getTopic());
                eventResult.setSourceResult(ack);
                if (ack) {
                    eventCallback.onSuccess(eventResult);
                } else {
                    eventCallback.onFailure(eventResult, new RuntimeException(
                            "Broker nack message, seq=" + deliveryTag
                    ));
                }
            }
        });
        //long seqNo = producerAsyncChannel.getNextPublishSeqNo();
        producerAsyncChannel.basicPublish(exchange, routingKey, null, body);
    }

    @Override
    public void publish(T eventModel) {
        EventCallback eventCallback = eventModel.getEventCallback();
        EventResult eventResult = new EventResult();
        try {
            byte[] body = serializer.serialize(eventModel, eventModel.getSerializeType());
            String exchange = eventModel.getTopic();
            String routingKey = eventModel.getTags();
            if(!StringUtils.hasText(routingKey)) {
                routingKey = exchange;
            }
            if(eventModel.isEnableAsync()) {
                // 异步发送
                //sendAsync(eventModel, exchange, routingKey, body);
                asyncSender.asyncPublishWithConfirm(exchange, routingKey, body, eventCallback);
            } else {
                // 同步阻塞发送
                producerChannel.confirmSelect();
                long seqNo = producerChannel.getNextPublishSeqNo();
                producerChannel.basicPublish(exchange, routingKey, null, body);
                producerChannel.waitForConfirms();
                if(null != eventCallback) {
                    eventResult.setMessageId(String.valueOf(seqNo));
                    eventResult.setTopic(exchange);
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

    @Override
    public void close() throws Exception {
        if(producerChannel != null) {
            try {
                producerChannel.close();
            } catch (Throwable ignored) { }
        }
        if(producerAsyncChannel != null) {
            try {
                producerAsyncChannel.close();
            } catch (Throwable ignored) { }
        }
        if(asyncSender != null) {
            try {
                asyncSender.close();
            } catch (Throwable ignored) { }
        }
        if(producerConnection != null) {
            try {
                producerConnection.close();
            } catch (Throwable ignored) { }
        }
        for (Channel consumerChannel : consumerChannels) {
            try {
                consumerChannel.close();
            } catch (Throwable ignored) { }
        }
        if(consumerConnection != null) {
            try {
                consumerConnection.close();
            } catch (Throwable ignored) { }
        }
    }
}
