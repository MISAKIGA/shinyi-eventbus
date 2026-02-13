package com.shinyi.eventbus.exception;

/** error code interface */
public interface ErrorCode {
    /**
     * Get error code
     *
     * @return error code
     */
    String getCode();

    /**
     * Get error description
     *
     * @return error description
     */
    String getDescription();

    default String getErrorMessage() {
        return String.format("ErrorCode:[%s], ErrorDescription:[%s]", getCode(), getDescription());
    }
}
