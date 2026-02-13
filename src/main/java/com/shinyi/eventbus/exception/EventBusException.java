package com.shinyi.eventbus.exception;



import java.util.Map;

/**
 * @author MSGA
 */
public class EventBusException extends BaseException {

    public EventBusException(ErrorCode errorCode, Map<String, String> params) {
        super(errorCode, params);
    }


    public EventBusException(ErrorCode errorCode, Map<String, String> params, Throwable cause) {
        super(errorCode, params, cause);
    }

    public EventBusException(ErrorCode errorCode, String errorMessage) {
        super(errorCode, errorMessage);
    }

    public EventBusException(ErrorCode errorCode, String errorMessage, Throwable cause) {
        super(errorCode, errorMessage, cause);
    }

    public EventBusException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
