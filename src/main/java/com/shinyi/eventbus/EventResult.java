package com.shinyi.eventbus;

import lombok.Data;

/**
 * @author MSGA
 */
@Data
public class EventResult {
    private String messageId;
    private String topic;
    /**
     * MQ Result 源对象
     */
    private Object sourceResult;
}
