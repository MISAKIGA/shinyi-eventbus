package com.shinyi.eventbus.support;


import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

import java.lang.reflect.UndeclaredThrowableException;

@Slf4j
public class SimpleErrorHandler implements ErrorHandler {
    @Override
    public void handleError(Throwable t) {
        if(t instanceof UndeclaredThrowableException) {
            UndeclaredThrowableException throwableException = (UndeclaredThrowableException) t;
            throw new RuntimeException(throwableException.getUndeclaredThrowable());
        }
        throw new RuntimeException(t);
    }
}
