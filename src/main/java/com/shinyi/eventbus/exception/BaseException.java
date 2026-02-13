package com.shinyi.eventbus.exception;


import java.util.HashMap;
import java.util.Map;

/** Shinyi global exception, used to tell user more clearly error messages */
public class BaseException extends RuntimeException {


    private final ErrorCode errorCode;
    private final Map<String, String> params;

    public BaseException(ErrorCode ErrorCode, String errorMessage) {
        super(ErrorCode.getErrorMessage() + " - " + errorMessage);
        this.errorCode = ErrorCode;
        this.params = new HashMap<>();
        ExceptionParamsUtil.assertParamsMatchWithDescription(
                ErrorCode.getDescription(), params);
    }

    public BaseException(
            ErrorCode ErrorCode, String errorMessage, Throwable cause) {
        super(ErrorCode.getErrorMessage() + " - " + errorMessage, cause);
        this.errorCode = ErrorCode;
        this.params = new HashMap<>();
        ExceptionParamsUtil.assertParamsMatchWithDescription(
                ErrorCode.getDescription(), params);
    }

    public BaseException(ErrorCode ErrorCode, Throwable cause) {
        super(ErrorCode.getErrorMessage(), cause);
        this.errorCode = ErrorCode;
        this.params = new HashMap<>();
        ExceptionParamsUtil.assertParamsMatchWithDescription(
                ErrorCode.getDescription(), params);
    }

    public BaseException(
            ErrorCode ErrorCode, Map<String, String> params) {
        super(ExceptionParamsUtil.getDescription(ErrorCode.getErrorMessage(), params));
        this.errorCode = ErrorCode;
        this.params = params;
    }

    public BaseException(
            ErrorCode ErrorCode, Map<String, String> params, Throwable cause) {
        super(
                ExceptionParamsUtil.getDescription(ErrorCode.getErrorMessage(), params),
                cause);
        this.errorCode = ErrorCode;
        this.params = params;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, String> getParams() {
        return params;
    }
}
