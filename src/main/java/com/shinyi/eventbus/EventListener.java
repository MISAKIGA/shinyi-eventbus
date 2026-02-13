package com.shinyi.eventbus;


import java.util.Collection;
import java.util.List;

/**
 * 消费接口
 * @author MSGA
 * @param <T> 消息模型
 */
public interface EventListener<T> extends java.util.EventListener {

    /**
     * 实例 bean name, 为空则注册到所有默认驱动器进行监听
     */
    default Collection<String> registryBeanName() {
        return null;
    }

    /**
     * 订阅 Topic
     *   Rabbit 会合并 group.topic 作为 queue 以确保互斥消费
     *
     * @return topic
     */
    String topic();

    /**
     * 消费消息
     *
     * @param message 消息模型
     */
    void onMessageBatch(List<T> message) throws Exception;

    /**
     * 消费消息
     *
     * @param message 消息模型
     */
    void onMessage(T message) throws Exception;

    default String appName() { return ""; }

    default String serializeType() { return "DEFAULT"; }

    /**
     * 消费模式 PUSH PULL
     */
    default String consumerMode() {
        return "PUSH";
    }

    /**
     * 消费组
     *   Rabbit 会合并 group.topic 作为 queue 以确保互斥消费
     */
    default String group() {
        return "DEFAULT";
    }

    /**
     * 事件实体类型用于序列化，为空则传输原始消息对象
     */
    Class<?> entityType();

    default String offset() { return ""; }

    // -------------- RocketMQ

    /**
     * 如果驱动为 RocketMQ 此参数会传递
     */
    default String tags() { return ""; }

    // -------------- RabbitMQ 语义

    /**
     * Rabbit 队列
     */
    default String queue() { return ""; }
    /**
     * Rabbit 交换机
     */
    default String exchange() { return ""; }
    /**
     * Rabbit 交换机类型
     */
    default String exchangeType() { return "direct"; }
    /**
     * Rabbit 路由Key
     */
    default String routingKey() { return ""; }
    /**
     * Rabbit 是否持久化
     */
    default boolean isDurable() { return true; }
    /**
     * Rabbit 消费消息后是否删除
     */
    default boolean autoDelete() { return false; }


}