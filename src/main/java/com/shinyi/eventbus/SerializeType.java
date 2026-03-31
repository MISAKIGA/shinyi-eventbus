package com.shinyi.eventbus;

/**
 * 序列化类型枚举（
 *
 * @author MSGA
 */
public enum SerializeType {

    /**
     * 对称模式（推荐）：只序列化/反序列化 entity
     * 推荐用于跨 MQ 通信，与 EVENT 模式等效
     */
    EVENT("EVENT"),
    /**
     * 废弃：默认模式，序列化整个 EventModel JSON，反序列化时先尝试 EventModel 再回退到 entity
     * 存在序列化不对称问题，建议使用 EVENT 模式替代
     */
    @Deprecated
    DEFAULT("DEFAULT"),
    /**
     * 废弃：仅支持 String/byte[] 类型的简单序列化
     * 建议使用 EVENT 或 RAW 模式替代
     */
    @Deprecated
    BASIC("BASIC"),
    /**
     * JSON 模式：与 EVENT 等效，语义更通用
     * 推荐使用 EVENT 模式以保持命名一致性
     */
    @Deprecated
    JSON("JSON"),
    MSG("MSG"),
    /**
     * RAW: 直接发送 entity 原始字节，不进行 JSON 包装
     * 高性能模式，需要生产者和消费者约定相同的序列化格式
     */
    RAW("RAW");

    private final String type;

    SerializeType(String type) {
        this.type = type;
    }

    /**
     * 获取与注解配置完全一致的字符串值
     */
    public String getType() {
        return type;
    }

    /**
     * 根据字符串值匹配枚举（兼容大小写不敏感）
     */
    public static SerializeType fromType(String type) {
        for (SerializeType value : values()) {
            if (value.type.equalsIgnoreCase(type)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid serialize type: " + type);
    }

    /**
     * 枚举描述信息（与原注释一致）
     */
    @Override
    public String toString() {
        return getType();
    }
}
