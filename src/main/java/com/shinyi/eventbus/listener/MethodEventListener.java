package com.shinyi.eventbus.listener;

import cn.hutool.core.collection.CollectionUtil;
import com.shinyi.eventbus.EventBusContext;
import com.shinyi.eventbus.EventModel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * @author MSGA
 */
public class  MethodEventListener extends ExecutableEventListener<Object> {

    private final Object target;
    private final Method method;
    private final String topic;
    private final String group;
    private final String tags;
    private final Class<?> entityType;
    private final String consumerMode;
    private final String[] registerBeanName;
    private final String appName;
    private final String serializeType;
    private final String offset;
    private final String queue;
    private final String exchange;
    private final String exchangeType;
    private final String routingKey;
    private final boolean durable;
    private final boolean autoDelete;

    public MethodEventListener(Object target, Method method, String topic, Class<?> entityType, String group,
                               String tags, String consumerMode, String[] registerBeanName, String appName,
                               String serializeType, String offset, String queue, String exchange, String exchangeType,
                               String routingKey, boolean durable, boolean autoDelete) {
        this.target = target;
        this.method = method;
        this.topic = topic;
        this.entityType = entityType;
        this.group = group;
        this.tags = tags;
        this.consumerMode = consumerMode;
        this.registerBeanName = registerBeanName;
        this.appName = appName;
        this.serializeType = serializeType;
        this.offset = offset;
        this.queue = queue;
        this.exchange = exchange;
        this.exchangeType = exchangeType;
        this.routingKey = routingKey;
        this.durable = durable;
        this.autoDelete = autoDelete;
    }

    @Override
    public boolean autoDelete() {
        return this.autoDelete;
    }

    @Override
    public boolean isDurable() {
        return durable;
    }

    @Override
    public String queue() {
        return this.queue;
    }

    @Override
    public String exchange() {
        return exchange;
    }

    @Override
    public String exchangeType() {
        return exchangeType;
    }

    @Override
    public String routingKey() {
        return routingKey;
    }

    @Override
    public Collection<String> registryBeanName() {
        return CollectionUtil.newArrayList(registerBeanName);
    }

    @Override
    public String offset() {
        return this.offset;
    }

    @Override
    public String tags() {
        return this.tags;
    }

    @Override
    public String serializeType() {
        return this.serializeType;
    }

    @Override
    public String appName() {
        return this.appName;
    }

    @Override
    public String consumerMode() {
        return this.consumerMode;
    }

    @Override
    public String group() {
        return this.group;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Class<?> entityType() {
        return this.entityType;
    }

    @Override
    protected void handle(List<EventModel<Object>> messages) throws Throwable {
        try {
            EventBusContext.setContext(EventBusContext.builder().messageModel(messages).build());
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length > 0) {
                Object[] paramValues = new Object[paramTypes.length];
                paramValues[0] = messages;
                method.invoke(target, paramValues);
            } else {
                method.invoke(target);
            }
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        } finally {
            EventBusContext.clearContext();
        }
    }
}
