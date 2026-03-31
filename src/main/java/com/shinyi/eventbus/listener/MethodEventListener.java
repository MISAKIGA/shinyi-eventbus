package com.shinyi.eventbus.listener;

import cn.hutool.core.collection.CollectionUtil;
import com.shinyi.eventbus.EventBusContext;
import com.shinyi.eventbus.EventModel;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author MSGA
 */
@Slf4j
@SuppressWarnings("unchecked")
public class MethodEventListener extends ExecutableEventListener<Object> {

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
    private final boolean exactlyOnce;
    private final int commitBatchSize;
    private final boolean passEntity;  // 是否传递 entity 实体还是 EventModel

    // 缓存参数类型信息，避免每次 handle() 调用时重新计算
    private Class<?> paramElementType;
    private boolean shouldExtractEntity;  // 是否需要从 EventModel 提取 entity

    public MethodEventListener(Object target, Method method, String topic, Class<?> entityType, String group,
                               String tags, String consumerMode, String[] registerBeanName, String appName,
                               String serializeType, String offset, String queue, String exchange, String exchangeType,
                               String routingKey, boolean durable, boolean autoDelete,
                               boolean exactlyOnce, int commitBatchSize, boolean passEntity) {
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
        this.exactlyOnce = exactlyOnce;
        this.commitBatchSize = commitBatchSize;
        this.passEntity = passEntity;

        // Check if method parameter is List type for batch processing
        checkMethodParameterType();
    }

    private void checkMethodParameterType() {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            Class<?> firstParamType = paramTypes[0];
            if (!List.class.isAssignableFrom(firstParamType)) {
                log.warn("[EventBusListener] 方法 {} 的第一个参数类型 {} 不是 List，建议使用 List<EventModel> 格式以支持批量处理，提升性能。方法签名示例: public void onEvent(List<{}> events)",
                        method.getName(),
                        firstParamType.getSimpleName(),
                        entityType.getSimpleName());
            }
        }

        // 初始化参数类型缓存
        Type genericParamType = method.getGenericParameterTypes().length > 0
                ? method.getGenericParameterTypes()[0]
                : null;
        this.paramElementType = computeParameterElementType(genericParamType);

        // 判断是否需要提取 entity（在构造时计算一次，避免每次 handle() 重新计算）
        this.shouldExtractEntity = computeShouldExtractEntity(this.paramElementType, this.entityType);
    }

    /**
     * 计算是否需要从 EventModel 提取 entity。
     *
     * @param paramElementType 方法参数的元素类型（如 String.class, MyEvent.class）
     * @param entityType       注解中指定的 entityType
     * @return true 如果需要提取 entity，false 如果直接传递 EventModel
     */
    private boolean computeShouldExtractEntity(Class<?> paramElementType, Class<?> entityType) {
        // Case 0: passEntity=false，强制传递 EventModel，不提取
        if (!passEntity) {
            return false;
        }

        // Case 1: 参数是 List<EventModel> 或其子类型，不需要提取
        if (paramElementType == null || EventModel.class.isAssignableFrom(paramElementType)) {
            return false;
        }

        // Case 2: 参数类型是 Object.class（泛型擦除），无法提取
        if (paramElementType == Object.class) {
            return false;
        }

        // Case 3: 参数是具体类型（如 String, MyEvent）
        if (entityType != null && entityType != Void.class) {
            // 显式指定了 entityType，验证是否匹配
            return paramElementType.equals(entityType);
        }

        // Case 4: entityType 未指定，只有 String/byte[] 需要提取
        return paramElementType == String.class
                || paramElementType == byte[].class
                || paramElementType == Byte[].class;
    }

    @Override
    public boolean exactlyOnce() {
        return this.exactlyOnce;
    }

    @Override
    public int commitBatchSize() {
        return this.commitBatchSize;
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
                paramValues[0] = prepareParameter(messages);
                // For single-parameter methods, pass the argument directly (not as array)
                // to avoid Java reflection treating Object[] as the single argument
                if (paramTypes.length == 1) {
                    method.invoke(target, paramValues[0]);
                } else {
                    method.invoke(target, paramValues);
                }
            } else {
                method.invoke(target);
            }
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        } finally {
            EventBusContext.clearContext();
        }
    }

    /**
     * 根据方法参数类型准备要传入的参数值。
     * - 单参数方法（非 List）：返回第一条消息的 entity（或 EventModel）
     * - List 参数方法：返回提取后的实体列表或原始 EventModel 列表
     */
    private Object prepareParameter(List<EventModel<Object>> messages) {
        Class<?>[] paramTypes = method.getParameterTypes();

        // Case 1: 单参数方法（非 List）
        if (paramTypes.length == 1 && !List.class.isAssignableFrom(paramTypes[0])) {
            if (messages.isEmpty()) {
                return null;
            }
            EventModel<Object> first = messages.get(0);
            if (shouldExtractEntity && first.getEntity() != null) {
                return first.getEntity();
            }
            return first;
        }

        // Case 2: List 参数方法
        return extractEntitiesIfNeeded(messages);
    }

    /**
     * Extract entities from EventModels based on cached parameter type.
     * Uses pre-computed shouldExtractEntity flag to avoid repeated type analysis.
     */
    private Object extractEntitiesIfNeeded(List<EventModel<Object>> messages) {
        // Case 1: 不需要提取，直接返回 EventModel 列表
        if (!shouldExtractEntity) {
            return messages;
        }

        // Case 2: 需要提取 entity
        List<Object> entities = new ArrayList<>(messages.size());
        for (EventModel<Object> eventModel : messages) {
            if (eventModel != null && eventModel.getEntity() != null) {
                entities.add(eventModel.getEntity());
            }
        }
        return entities;
    }

    /**
     * Extract the element type from a generic parameter type.
     * For List<String>, returns String.class.
     * For List<EventModel<String>>, returns EventModel.class.
     */
    private Class<?> computeParameterElementType(Type genericParamType) {
        if (genericParamType == null) {
            return null;
        }

        if (genericParamType instanceof ParameterizedType) {
            ParameterizedType pType = (ParameterizedType) genericParamType;
            Type[] typeArgs = pType.getActualTypeArguments();
            if (typeArgs.length > 0) {
                Type elementType = typeArgs[0];
                if (elementType instanceof Class) {
                    return (Class<?>) elementType;
                }
            }
        }

        // For non-parameterized types (e.g., String.class, MyEvent.class), return the class itself
        if (genericParamType instanceof Class) {
            return (Class<?>) genericParamType;
        }

        return null;
    }
}
