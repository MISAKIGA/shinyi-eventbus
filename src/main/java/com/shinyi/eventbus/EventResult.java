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

    /**
     * 重置状态以便复用
     */
    public void reset() {
        this.messageId = null;
        this.topic = null;
        this.sourceResult = null;
    }
}
