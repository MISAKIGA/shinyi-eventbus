package com.shinyi.eventbus.config.rabbit;

import com.shinyi.eventbus.EventBusType;
import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.registry.RabbitMqEventListenerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;


/**
 * RocketMQ 自动装配
 * @author MSGA
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE+1)
@RequiredArgsConstructor
@ConditionalOnBean(RabbitMqConfig.class)
public class RabbitMqAutoConfiguration implements InitializingBean {

    private final RabbitMqConfig rabbitMqConfig;

    private final ApplicationContext applicationContext;

    private EventListenerRegistry<?> buildEventListenerRegistry(String beanName, RabbitMqConnectConfig rabbitMqConfig) {
        RabbitMqEventListenerRegistry<EventModel<?>> registry = new RabbitMqEventListenerRegistry<>(applicationContext, beanName, rabbitMqConfig);
        try {
            registry.init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    public void registerBeanDefinitions() {
        // 从 ApplicationContext 中获取 RocketMqConfig
        if (rabbitMqConfig.getConnectConfigs() != null) {
            rabbitMqConfig.getConnectConfigs().forEach((beanName, rocketMqConfig) -> {
                // 如果类型是 Spring 则需要注册到 Spring
                if (applicationContext instanceof GenericApplicationContext) {
                    // 注册驱动
                    ((GenericApplicationContext) applicationContext).registerBean(beanName,
                            EventListenerRegistry.class, () -> buildEventListenerRegistry(beanName, rocketMqConfig));
                    // 注册默认驱动
                    if(rocketMqConfig.getIsDefault()) {
                        String typeName = EventBusType.RABBITMQ.getTypeName();
                        ((GenericApplicationContext) applicationContext).registerBean(typeName,
                                EventListenerRegistry.class, () -> buildEventListenerRegistry(typeName, rocketMqConfig));
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
