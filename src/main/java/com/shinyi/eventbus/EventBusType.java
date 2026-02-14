package com.shinyi.eventbus;

import lombok.Getter;

/**
 * 事件总线类型
 * @author MSGA
 */
@Getter
public enum EventBusType {

    // 事件总线，使用时应该要考虑事件风暴(多实例重复推送)、线程安全、ThreadLocal 上下文传递等问题。
    // 注意，停机脚本如果用 Kill -9 , 需要考虑线程池任务丢失风险
    ALL("all", false,true),
    // ---------------- LOCAL Event Bus ----------------
    GUAVA("guava", true,true),
    SPRING("spring", true,true),
    // ---------------- Remote Event Bus ----------------
    REDIS("redis", false,true),
    RABBITMQ("rabbitmq", false,true),
    KAFKA("kafka", false,true),
    ROCKETMQ("rocketmq", false,true);

    private final boolean isLocalEventBus;
    private final String typeName;
    private final boolean enabled;

    EventBusType(String typeName, boolean isLocalEventBus,boolean enabled) {
        this.typeName = typeName;
        this.enabled = enabled;
        this.isLocalEventBus = isLocalEventBus;
    }

    public static EventBusType fromString(String typeName) {
        for (EventBusType type : EventBusType.values()) {
            if (type.getTypeName().equals(typeName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No matching constant for [" + typeName + "]");
    }

    public static String toString(EventBusType type) {
        return type != null ? type.getTypeName() : null;
    }
}
