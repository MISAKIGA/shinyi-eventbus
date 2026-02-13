package com.shinyi.eventbus;

/**
 * 序列化类型枚举（
 *
 * @author MSGA
 */
public enum SerializeType {

    DEFAULT("DEFAULT"),
    BASIC("BASIC"),
    JSON("JSON"),
    MSG("MSG");

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
