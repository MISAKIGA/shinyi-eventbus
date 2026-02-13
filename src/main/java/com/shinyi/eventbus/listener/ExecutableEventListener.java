package com.shinyi.eventbus.listener;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.eventbus.Subscribe;
import com.shinyi.eventbus.EventBusType;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.lang.NonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author MSGA
 */
@SuppressWarnings("unchecked")
@Slf4j
public abstract class ExecutableEventListener<T> extends BaseEventListener<EventModel<T>> {


    /**
     * 事件实体类型用于序列化
     */
    @Override
    public Class<?> entityType() {
        Class<?> clazz = getClass();
        // 遍历类层级，直到 Object
        while (clazz != Object.class) {
            // 1. 检查当前类实现的泛型接口
            for (Type type : clazz.getGenericInterfaces()) {
                Class<?> typeArgs = getaClass(type);
                if (typeArgs != null) { return typeArgs; }
            }

            // 2. 检查当前类的泛型父类
            Type superType = clazz.getGenericSuperclass();
            Class<?> typeArgs = getaClass(superType);
            if (typeArgs != null) { return typeArgs; }

            // 3. 向上遍历父类
            clazz = clazz.getSuperclass();
        }

        throw new IllegalStateException("无法获取泛型类型: " + getClass().getName());
    }

    private Class<?> getaClass(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType pType = (ParameterizedType) type;
            if (pType.getRawType() == ExecutableEventListener.class) {
                Type[] typeArgs = pType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<EventModel<T>>) typeArgs[0];
                }
            }
        }
        return null;
    }

    private boolean checkNotSupportGroup(EventModel<T> message) {
        // 本地事件总线不支持分组消费
        if(message.getDriveType().endsWith("#"+EventBusType.SPRING.getTypeName())) {
            return true;
        }
        return message.getDriveType().endsWith("#"+EventBusType.GUAVA.getTypeName());
    }

    private boolean checkGroup(EventModel<T> message) {
        if(null == message.getDriveType()) {
            log.warn("driveType 为空，将不处理该消息。 内容 {}", message);
            return false;
        }
        // 通常本地事件总线消费数据不支持获取group
        if(null == message.getGroup() && checkNotSupportGroup(message)) {
            return true;
        }
        return Objects.equals(group(), message.getGroup());
    }

    @Override
    public void onMessageBatch(List<EventModel<T>> messages) throws Exception {

        Map<String, List<EventModel<T>>> groupMap = new HashMap<>();
        String traceId = MDC.get("traceId");
        MDC.put("traceId", UUID.randomUUID().toString().replace("-",""));
        for (EventModel<T> message : messages) {
            log.debug("从 {} 收到 {} 消息：{}", message.getDriveType(), message.getTopic(), message.getEventId());

            if (checkGroup(message) && topic().equals(message.getTopic())){
                List<EventModel<T>> msgList = groupMap.getOrDefault(message.getGroup(), new ArrayList<>());
                msgList.add(message);
                groupMap.put(message.getTopic(), msgList);
            }
        }
        for (Map.Entry<String, List<EventModel<T>>> entry : groupMap.entrySet()) {
            long start = System.currentTimeMillis();
            try {
                handle(entry.getValue());
            } catch (Throwable e) {
                String errMsg = e.getClass().getName() + ":" + (StrUtil.isBlank(e.getMessage()) ? "" : e.getMessage());
                Map<String, String> err = MapUtil.of("ERR", errMsg);
                throw new EventBusException(EventBusExceptionType.LISTENER_BIZ_ERROR, err, e);
            } finally {
                log.info("Topic: {} 消息处理耗时：{}，数据量：{}", entry.getKey(), System.currentTimeMillis() - start, entry.getValue().size());
            }
        }
        MDC.put("traceId", traceId);
    }

    @Subscribe
    @Override
    public void onMessage(EventModel<T> message) throws Exception {
        onMessageBatch(Collections.singletonList(message));
    }

    @Override
    public void onApplicationEvent(@NonNull PayloadApplicationEvent<EventModel<T>> event) {
        try {
            onMessage(event.getPayload());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *  具体消息处理方法
     * @param message 事件模型
     */
    protected abstract void handle(List<EventModel<T>> message) throws Throwable;
}
