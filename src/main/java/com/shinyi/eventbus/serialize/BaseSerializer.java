package com.shinyi.eventbus.serialize;

import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
public class BaseSerializer implements Serializer {

    @Override
    public byte[] serialize(EventModel<?> object, String serializeType) {
        serializeType = Optional.ofNullable(serializeType).orElse("DEFAULT");
        switch (serializeType) {
            case "EVENT":
                // EVENT 模式：与 JSON 相同，只序列化 entity
                // 推荐的对称模式，与 deserialize 的 EVENT 模式对应
            case "JSON":
                // JSON 模式：只序列化 entity，不包装 EventModel JSON
                // 与 deserialize 的 JSON 模式对应（deserialize 只反序列化 entity）
                if (object.getEntity() == null) {
                    return new byte[0];
                }
                return JsonUtils.toJsonString(object.getEntity()).getBytes(StandardCharsets.UTF_8);
            case "RAW":
                // RAW 模式：直接序列化 entity 原始字节，不包装 EventModel JSON
                // 高性能模式，适用于 Kafka 等字节导向的 MQ
                if (object.getEntity() == null) {
                    return new byte[0];
                }
                return serializeEntityToBytes(object.getEntity());
            case "BASIC":
                return String.valueOf(object).getBytes(StandardCharsets.UTF_8);
            case "DEFAULT":
            default:
                return JsonUtils.toJsonString(object).getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 将 entity 序列化为字节数组
     * 优先使用二进制序列化，次选 JSON
     */
    private byte[] serializeEntityToBytes(Object entity) {
        if (entity instanceof byte[]) {
            return (byte[]) entity;
        } else if (entity instanceof String) {
            return ((String) entity).getBytes(StandardCharsets.UTF_8);
        } else {
            // 其他类型使用 JSON 序列化
            return JsonUtils.toJsonString(entity).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public EventModel<?> deserialize(byte[] bytes, String serializeType, Class<?> entityType) {
        EventModel<?> eventModel;
        switch (serializeType) {
            case "EVENT":
                // EVENT 模式：与 JSON 相同，只反序列化 entity
                // 推荐的对称模式，与 serialize 的 EVENT 模式对应
            case "JSON":
                eventModel = EventModel.build(null, JsonUtils.parseObject(new String(bytes), entityType));
                break;
            case "RAW":
                // RAW 模式：直接反序列化 entity 原始字节
                eventModel = EventModel.build(null, deserializeEntityFromBytes(bytes, entityType));
                break;
            case "MSG":
                // 取消息体
                eventModel = EventModel.build(null, null);
                break;
            case "BASIC":
                eventModel = EventModel.build(null, deserialize2Basic(bytes, entityType));
                break;
            case "DEFAULT":
            default:
                // 取消息内容
                String jsonStr = new String(bytes);
                eventModel = JsonUtils.parseObject(jsonStr, EventModel.class, entityType);
                if(eventModel == null) {
                    log.warn("JSON 消息解析 -> {} 失败：{}", entityType, jsonStr);
                    eventModel = EventModel.build(null, null);
                }
                // 能走到这说明是一个json字符串，否则报错了
                if(eventModel.getEventId() == null && eventModel.getEntity() == null) {
                    // 可能是不支持转为 EventModel，尝试使用 JSON 模式
                    eventModel = EventModel.build(null, JsonUtils.parseObject(new String(bytes), entityType));
                }
        }
        return eventModel;
    }

    /**
     * 从字节数组反序列化 entity
     * 优先使用二进制反序列化，次选 JSON
     */
    private Object deserializeEntityFromBytes(byte[] bytes, Class<?> entityType) {
        if (entityType == null) {
            return bytes;
        }
        if (entityType == byte[].class) {
            return bytes;
        } else if (entityType == String.class) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            // 其他类型使用 JSON 反序列化
            return JsonUtils.parseObject(new String(bytes), entityType);
        }
    }

    private Object deserialize2Basic(byte[] bytes, Class<?> entityType) {
        Object body;
        // 根据实体类型处理字节数据
        if (entityType == String.class) {
            body = new String(bytes, StandardCharsets.UTF_8);
        } else if (entityType == byte[].class) {
            body = bytes;
        } else if (entityType == Byte[].class) {
            // 转换为Byte对象数组
            Byte[] byteArray = new Byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                byteArray[i] = bytes[i];
            }
            body = byteArray;
        } else {
            throw new IllegalArgumentException("BASIC序列化不支持实体类型: " + entityType);
        }
        return body;
    }
}
