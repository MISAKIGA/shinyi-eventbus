package com.shinyi.eventbus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;

/**
 * 事件模型, feature: 考虑支持异步响应对象
 * @param <T> 事件对象模型类型
 * @author MSGA
 */
@Data
public class EventModel<T> implements Serializable {

    private static final long serialVersionUID = 9146940924770076711L;

    /**
     * 事件编码（可做为链路追踪标识）
     */
    protected String eventId;

    /**
     * 启用异步执行
     */
    @JsonIgnore
    protected transient boolean enableAsync;

    /**
     *  事件发布主题
     */
     protected String topic;

    /**
     * topic分组
     */
    protected String group;

    /**
     * tags 如果使用 RocketMQ 会传递 tags 参数
     */
    @JsonIgnore
    protected transient String tags;

    /**
     * 使用的消息驱动类型
     */
    protected String driveType;

     /**
      *  事件对象模型
      */
     protected T entity;

    /**
     * 事件回调
     */
    @JsonIgnore
    protected transient EventCallback eventCallback;

    /**
     * 事件源对象
     */
    @JsonIgnore
    protected transient byte[] rawData;

    /**
     * 序列化类型: 默认是将 EventModel 序列化成JSON字符串，JSON 是将entity内容序列化成字符串，RAW 则是将直接entity内容转为字符串
     */
    @JsonIgnore
    protected transient String serializeType;

    /**
     * 创建事件模型，开启异步
     * @param topic 话题
     * @param entity 事件内容
     * @return 事件模型
     * @param <B> 事件领域对象
     */
    public static <B> EventModel<B> build(String topic, B entity) {
        return build(topic, entity, null, true);
    }

    /**
     * 创建事件模型，开启异步
     * @param topic 话题
     * @param entity 事件内容
     * @return 事件模型
     * @param <B> 事件领域对象
     */
    public static <B> EventModel<B> build(String topic, B entity, boolean enableAsync) {
        return build(topic, entity, null, enableAsync);
    }

    public static <B> EventModel<B> build(String topic, B entity, String eventId, boolean enableAsync) {
        return build(topic, entity, eventId, enableAsync, null, null);
    }

    public static <B> EventModel<B> build(String topic, B entity, boolean enableAsync,
                                          EventCallback eventCallback) {
        return build(topic, entity, null, enableAsync, null, eventCallback);
    }

    public static <B> EventModel<B> build(String topic, B entity, boolean enableAsync,
                                          EventCallback eventCallback, String serializeType) {
        return build(topic, entity, null, enableAsync, serializeType, eventCallback);
    }

    public static <B> EventModel<B> build(String topic, B entity, String eventId, boolean enableAsync,
                                          String serializeType, EventCallback eventCallback) {
        final EventModel<B> eventModel = new EventModel<>();
        eventModel.eventId = eventId;
        eventModel.topic = topic;
        eventModel.entity = entity;
        eventModel.enableAsync = enableAsync;
        eventModel.eventCallback = eventCallback;
        eventModel.serializeType = serializeType;
        return eventModel;
    }
}
