package com.shinyi.eventbus.listener;

import com.shinyi.eventbus.EventListener;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * @author MSGA
 */
public abstract class BaseEventListener<T> implements EventListener<T>, ApplicationListener<PayloadApplicationEvent<T>> {
}
