package com.shinyi.eventbus.support;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.shinyi.eventbus.EventBusType;
import com.shinyi.eventbus.EventListener;
import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.anno.EventBusListener;
import com.shinyi.eventbus.exception.EventBusException;
import com.shinyi.eventbus.exception.EventBusExceptionType;
import com.shinyi.eventbus.listener.MethodEventListener;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件注册中心
 * @author MSGA
 * @since v1.1
 */
@Lazy
@Slf4j
@SuppressWarnings("unchecked")
public class EventListenerRegistryManager implements SmartLifecycle, ApplicationContextAware, AutoCloseable {

    private final Map<String, EventListenerRegistry<EventModel<?>>> ALL_EVENT_DRIVE_REGISTRY = new ConcurrentHashMap<>();

    private final Map<String, EventListener<EventModel<Object>>> EVENT_LISTENERS_MAP = new ConcurrentHashMap<>();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Environment env;

    private ApplicationContext applicationContext;

    public void publish(EventBusType eventBusType, EventModel<?> event) throws EventBusException {
        if(!eventBusType.isEnabled()) { throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_DISABLE_ERROR, MapUtil.of("EL", eventBusType.getTypeName())); }
        publish(eventBusType.getTypeName(), event);
    }


    public void publish(String eventBusTypeName, EventModel<?> event) throws EventBusException {
        if(null == event) { throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_EVENT_NULL_ERROR, "事件模型不能为空"); }
        if(null == event.getEventId()) {
            String traceId = MDC.get("traceId");
            if(StrUtil.isBlank(traceId)) {
                traceId = IdUtil.randomUUID().replace("-", "");
            }
            event.setEventId(traceId);
        }
        final long start = System.currentTimeMillis();
        try {
            EventListenerRegistry<EventModel<?>> eventModelEventListenerRegistry = ALL_EVENT_DRIVE_REGISTRY.get(eventBusTypeName);
            if(eventModelEventListenerRegistry == null) {
                throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_NOT_FOUND_ERROR, MapUtil.of("EL", eventBusTypeName));
            }
            event.setDriveType(eventBusTypeName+"#"+eventModelEventListenerRegistry.getEventBusType().getTypeName());
            String syncDescribe = event.isEnableAsync() ? "异步" : "同步";
            log.info("{} 开始发布 {} {} 事件：{}", event.getTopic(), eventBusTypeName, syncDescribe, event.getEventId());
            eventModelEventListenerRegistry.publish(event);
        } catch (Exception e) {
            if(event.isEnableAsync()) {
                throw new EventBusException(EventBusExceptionType.EVENTBUS_PUBLISH_ERROR, e);
            } else {
                // 非异步模式直接抛出业务异常
                throw new EventBusException(EventBusExceptionType.LISTENER_BIZ_ERROR, MapUtil.of("ERR", e.getMessage()), e);
            }
        } finally {
            log.info("{} 事件发布 {} 耗时：{}", eventBusTypeName, event.getTopic(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public void start() {

        // 初始化事件注册中心/驱动
        initEventListenerRegistry();
        // 初始化监听器
        initEventListenerMethodRepository(applicationContext);
        // 加载监听器
        loadAllEventListener();

        // 设置状态
        isRunning.set(true);
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.env = applicationContext.getEnvironment();
    }

    @Override
    public void close() {
        // 关闭所有驱动（spring也会调用，所以会重复关闭，影响不大）
        ALL_EVENT_DRIVE_REGISTRY.forEach((n,r) -> {
            try {
                r.close();
            } catch (Exception e) {
                log.error("shinyi eventbus listener registry close error.", e);
            }
        });
        isRunning.set(false);
    }

    public void initEventListenerMethodRepository(ApplicationContext applicationContext) {

        if (applicationContext == null) { return; }

        applicationContext.getBeansOfType(EventListener.class).forEach((k, v) -> {
            if (v != null) { EVENT_LISTENERS_MAP.put(k, v); }
        });

        // init job handler from method
        String[] beanDefinitionNames = applicationContext.getBeanNamesForType(Object.class, false, true);
        for (String beanDefinitionName : beanDefinitionNames) {

            // get bean
            Object bean;
            Lazy onBean = applicationContext.findAnnotationOnBean(beanDefinitionName, Lazy.class);
            if (onBean != null){
                log.debug("shinyi annotation scan, skip @Lazy Bean:{}", beanDefinitionName);
                continue;
            } else {
                bean = applicationContext.getBean(beanDefinitionName);
            }

            // filter method
            Map<Method, EventBusListener> annotatedMethods = null;
            try {
                annotatedMethods = MethodIntrospector.selectMethods(bean.getClass(),
                        (MethodIntrospector.MetadataLookup<EventBusListener>) method -> AnnotatedElementUtils.findMergedAnnotation(method, EventBusListener.class)
                );
            } catch (Throwable ex) {
                log.error("shinyi method-EventBusListener resolve error for bean[{}].", beanDefinitionName, ex);
            }
            if (annotatedMethods == null || annotatedMethods.isEmpty()) {
                continue;
            }

            // generate and regist method job handler
            for (Map.Entry<Method, EventBusListener> methodEntry : annotatedMethods.entrySet()) {
                Method executeMethod = methodEntry.getKey();
                EventBusListener eventListener = methodEntry.getValue();
                // register
                eventListenerRegister(eventListener, bean, executeMethod);
            }
        }
    }

    private void eventListenerRegister(EventBusListener eventListener, Object bean, Method executeMethod) {

        if (eventListener == null) { return; }

        String registerBeanName = getRegisterBeanName(eventListener, bean, executeMethod);

        executeMethod.setAccessible(true);

        // registry listener
        EventListener<EventModel<Object>> listener = new MethodEventListener(bean, executeMethod,
                env.resolveRequiredPlaceholders(eventListener.topic()),
                eventListener.entityType(),
                env.resolveRequiredPlaceholders(eventListener.group()),
                env.resolveRequiredPlaceholders(eventListener.tags()),
                env.resolveRequiredPlaceholders(eventListener.consumerMode()),
                eventListener.name(),
                env.resolveRequiredPlaceholders(eventListener.appName()),
                eventListener.deserializeType().getType(),
                env.resolveRequiredPlaceholders(eventListener.offset()),
                env.resolveRequiredPlaceholders(eventListener.queue()),
                env.resolveRequiredPlaceholders(eventListener.exchange()),
                env.resolveRequiredPlaceholders(eventListener.exchangeType()),
                env.resolveRequiredPlaceholders(eventListener.routingKey()),
                eventListener.durable(),
                eventListener.autoDelete()
        );
        EVENT_LISTENERS_MAP.put(registerBeanName, listener);

        // 如果类型是 Spring 则需要注册到 Spring
        if (applicationContext instanceof GenericApplicationContext) {
            ((GenericApplicationContext) applicationContext).registerBean(registerBeanName, EventListener.class, () -> listener);
        }
    }

    private String getRegisterBeanName(EventBusListener eventListener, Object bean, Method executeMethod) {
        String registerBeanName = eventListener.beanName();
        if(registerBeanName.trim().isEmpty()) {
            registerBeanName = bean.getClass().getSimpleName() +"_"+ executeMethod.getName();
        }

        if (EVENT_LISTENERS_MAP.containsKey(registerBeanName)) {
            throw new RuntimeException("shinyi eventbus listener[" + registerBeanName + "] naming conflicts.");
        }
        return registerBeanName;
    }

    private void initEventListenerRegistry() {
        applicationContext.getBeansOfType(EventListenerRegistry.class)
                .forEach((k, v) -> {
                    if(null != v) {
                        ALL_EVENT_DRIVE_REGISTRY.put(k.replace("EventListenerRegistry", ""), v);
                    }
                });
    }

    private void loadAllEventListener() {
        // 加载所有事件监听器
        List<EventListener<EventModel<?>>> values = new ArrayList<>();
        for (EventListener<? extends EventModel<?>> value : EVENT_LISTENERS_MAP.values()) {
            values.add((EventListener<EventModel<?>>) value);
        }
        ALL_EVENT_DRIVE_REGISTRY.forEach((k, v) -> {
            //log.info("初始化 {} 事件监听器组件。", k);
            if(k.contains("#")) {
                throw new EventBusException(EventBusExceptionType.EVENTBUS_DRIVER_INIT_ERROR, MapUtil.of("EL", k+"不能带有#号！"));
            }
            if (null != v) {
                long start = System.currentTimeMillis();
                v.initRegistryEventListener(values);
                log.info("{} 事件监听组件初始化完成。 耗时：{}", k, System.currentTimeMillis()-start);
            }
        });
    }
}
