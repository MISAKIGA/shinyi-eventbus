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

    /**
     * 批量发布消息
     * @param events 消息列表
     * @param callback 批量回调
     * @throws UnsupportedOperationException 默认实现不支持批量语义，子类必须重写
     */
    default void publishBatch(List<T> events, BatchEventCallback callback) {
        throw new UnsupportedOperationException(
            "publishBatch is not supported by this registry. Use a registry that implements batch publish.");
    }

    /**
     * 刷新缓冲区
     */
    default void flush() {
        // 默认实现为空
    }
}
