package com.shinyi.eventbus;

import java.util.List;

/**
 * 事件监听器注册中心
 * @author MSGA
 * @param <T>
 */
public interface EventListenerRegistry <T> extends AutoCloseable {

    /**
     * 获取事件总线类型
     */
    EventBusType getEventBusType();

    /**
     * 初始化监听器
     * @param listener 监听器
     */
    void initRegistryEventListener(List<EventListener<T>> listener);

    /**
     * 发布消息
     * @param t 消息模型
     */
    void publish(T t);
}
