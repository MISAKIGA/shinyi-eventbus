package com.shinyi.eventbus;

import com.alibaba.ttl.TransmittableThreadLocal;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 当前事件上下文，通过此工具获取到事件相关信息
 * @author MSGA
 */
@Slf4j
@SuppressWarnings("all")
@Builder
public class EventBusContext {

    private static InheritableThreadLocal<EventBusContext> contextHolder;

    static {
        try {
            contextHolder = new TransmittableThreadLocal<>();
        } catch (NoClassDefFoundError e) {
            contextHolder = new InheritableThreadLocal<>();
            log.error("TransmittableThreadLocal加载失败，上下文管理使用InheritableThreadLocal。");
        }
    }

    private List<EventModel<Object>> messageModel;

    public <T> List<EventModel<T>> getMessageModelList() {
        return (List) Collections.unmodifiableList(messageModel);
    }

    public static void clearContext() {
        contextHolder.remove();
    }

    public static void setContext(EventBusContext eventBusContext){
        contextHolder.set(eventBusContext);
    }

    public static EventBusContext getContext(){
        return contextHolder.get();
    }

}
