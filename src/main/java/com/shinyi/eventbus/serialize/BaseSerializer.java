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
            case "BASIC":
                return String.valueOf(object).getBytes(StandardCharsets.UTF_8);
            case "JSON":
                return JsonUtils.toJsonString(object).getBytes(StandardCharsets.UTF_8);
            case "DEFAULT":
            default:
                return JsonUtils.toJsonString(object).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public EventModel<?> deserialize(byte[] bytes, String serializeType, Class<?> entityType) {
        EventModel<?> eventModel;
        switch (serializeType) {
            case "MSG":
                // 取消息体
                eventModel = EventModel.build(null, null);
                break;
            case "BASIC":
                eventModel = EventModel.build(null, deserialize2Basic(bytes, entityType));
                break;
            case "JSON":
                eventModel = EventModel.build(null, JsonUtils.parseObject(new String(bytes), entityType));
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
