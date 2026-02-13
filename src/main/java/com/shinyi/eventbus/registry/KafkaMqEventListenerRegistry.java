package com.shinyi.eventbus.registry;

import com.shinyi.eventbus.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * RockMq事件监听器注册器
 * @author MSGA
 * @param <T>
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected Executor executor;

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.ROCKETMQ;
    }


    public void setThreadPool(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        // ignore
    }

    @Override
    public void publish(T eventModel) {
        throw new RuntimeException("还没实现呢！！！");
    }

    @Override
    public void close() throws Exception {
        // do nothing
    }
}
