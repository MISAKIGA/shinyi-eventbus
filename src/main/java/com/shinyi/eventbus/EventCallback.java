package com.shinyi.eventbus;


/**
 * 事件回调
 * @author MSGA
 */
public interface EventCallback {

    /**
     * 成功
     */
    void onSuccess(EventResult eventResult);

    /**
     * 失败
     */
    void onFailure(EventResult eventResult, Throwable throwable);
}
