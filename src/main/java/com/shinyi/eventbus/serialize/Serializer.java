package com.shinyi.eventbus.serialize;

import com.shinyi.eventbus.EventModel;

/**
 * @author MSGA
 */
public interface Serializer {

    byte[] serialize(EventModel<?> object, String serializeType);

    EventModel<?> deserialize(byte[] bytes, String serializeType, Class<?> entityType);
}
