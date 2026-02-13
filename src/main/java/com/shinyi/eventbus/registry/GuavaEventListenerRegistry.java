package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import com.shinyi.eventbus.*;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * @author MSGA
 */
@Slf4j
@RequiredArgsConstructor
public class GuavaEventListenerRegistry <T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final String registryBeanName;
    protected EventBus guavaEventBus;
    protected EventBus guavaSynchronousEventBus;
    protected Executor executor;

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.GUAVA;
    }

    public void setThreadPool(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        if(listener == null) { return; }
        guavaEventBus = new AsyncEventBus(GuavaEventListenerRegistry.class.getName(),executor);
        guavaSynchronousEventBus = new EventBus();
        for (EventListener<T> l : listener) {
            if(CollectionUtil.isNotEmpty(l.registryBeanName()) && !CollectionUtil.contains(l.registryBeanName(), registryBeanName)) {
                log.debug("{} not match skip listener [{}]", registryBeanName, l);
                continue;
            }
            log.info("注册监听器：{}",l.getClass().getName());
            guavaEventBus.register(l);
            guavaSynchronousEventBus.register(l);
        }
    }

    @Override
    public void publish(T eventModel) {
        Optional<EventCallback> eventCallback = Optional.ofNullable(eventModel.getEventCallback());
        EventResult eventResult = new EventResult();
        eventResult.setTopic(eventModel.getTopic());
        eventResult.setMessageId(eventModel.getEventId());
        if(eventModel.isEnableAsync()) {
            try {
                guavaEventBus.post(eventModel);
                eventCallback.ifPresent(callback -> callback.onSuccess(eventResult));
            } catch (Exception e) {
                eventCallback.ifPresent(callback -> callback.onFailure(eventResult, e));
            }
        } else {
            guavaSynchronousEventBus.post(eventModel);
            eventCallback.ifPresent(callback -> callback.onSuccess(eventResult));
        }
    }

    @Override
    public void close() throws Exception {
        // do nothing
    }
}
