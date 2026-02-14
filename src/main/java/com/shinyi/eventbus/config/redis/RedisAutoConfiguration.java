package com.shinyi.eventbus.config.redis;

import com.shinyi.eventbus.EventBusType;
import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.registry.RedisMqEventListenerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Redis auto configuration
 * @author MSGA
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
@ConditionalOnBean(RedisConfig.class)
public class RedisAutoConfiguration implements InitializingBean {

    private final RedisConfig redisConfig;

    private final ApplicationContext applicationContext;

    private EventListenerRegistry<?> buildEventListenerRegistry(String beanName, RedisConnectConfig redisConnectConfig) {
        RedisMqEventListenerRegistry<EventModel<?>> registry = new RedisMqEventListenerRegistry<>(applicationContext, beanName, redisConnectConfig);
        try {
            registry.init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    public void registerBeanDefinitions() {
        if (redisConfig.getConnectConfigs() != null) {
            redisConfig.getConnectConfigs().forEach((beanName, redisConnectConfig) -> {
                if (applicationContext instanceof GenericApplicationContext) {
                    ((GenericApplicationContext) applicationContext).registerBean(beanName,
                            EventListenerRegistry.class, () -> buildEventListenerRegistry(beanName, redisConnectConfig));
                    if (redisConnectConfig.getIsDefault()) {
                        String typeName = EventBusType.REDIS.getTypeName();
                        ((GenericApplicationContext) applicationContext).registerBean(typeName,
                                EventListenerRegistry.class, () -> buildEventListenerRegistry(typeName, redisConnectConfig));
                    }
                }
            });
        }
    }

    @Override
    public void afterPropertiesSet() {
        registerBeanDefinitions();
    }
}
