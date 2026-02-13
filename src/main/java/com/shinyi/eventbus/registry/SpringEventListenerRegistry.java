package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.listener.BaseEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * spring事件监听器注册器
 * @author MSGA
 * @param <T>
 */
@Slf4j
@RequiredArgsConstructor
public class SpringEventListenerRegistry <T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected Executor executor;

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.SPRING;
    }


    public void setThreadPool(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        if(listener == null) { return; }
        if(applicationContext == null) {
            log.warn("[SpringEventListenerRegistry] Oops! current env is not spring env so skip init that .");
            return;
        }
        ApplicationEventMulticaster eventMulticaster = applicationContext.getBean(ApplicationEventMulticaster.class);
        for (EventListener<T> l : listener) {
            if(CollectionUtil.isNotEmpty(l.registryBeanName()) && !CollectionUtil.contains(l.registryBeanName(), registryBeanName)) {
                log.debug("{} not match skip listener [{}]", registryBeanName, l);
                continue;
            }
            if(l instanceof BaseEventListener) {
                BaseEventListener<T> baseEventListener = (BaseEventListener<T>)l;
                eventMulticaster.addApplicationListener(baseEventListener);
            }
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
                executor.execute(() -> applicationContext.publishEvent(eventModel));
                eventCallback.ifPresent(callback -> callback.onSuccess(eventResult));
            } catch (Exception e) {
                eventCallback.ifPresent(callback -> callback.onFailure(eventResult, e));
            }
        } else {
            //--- 通常 Spring 需 @Async 注解开启异步，自己封装不带 @Async，此处当作非异步任务触发执行。
            applicationContext.publishEvent(eventModel);
            eventCallback.ifPresent(callback -> callback.onSuccess(eventResult));
        }
    }

    @Override
    public void close() throws Exception {
        // do nothing
    }
}
