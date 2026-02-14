package com.shinyi.eventbus.config.kafka;

import com.shinyi.eventbus.EventBusType;
import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.registry.KafkaMqEventListenerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
@ConditionalOnBean(KafkaConfig.class)
public class KafkaAutoConfiguration implements InitializingBean {

    private final KafkaConfig kafkaConfig;

    private final ApplicationContext applicationContext;

    private EventListenerRegistry<?> buildEventListenerRegistry(String beanName, KafkaConnectConfig kafkaConnectConfig) {
        KafkaMqEventListenerRegistry<EventModel<?>> registry = new KafkaMqEventListenerRegistry<>(applicationContext, beanName, kafkaConnectConfig);
        try {
            registry.init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    public void registerBeanDefinitions() {
        if (kafkaConfig.getConnectConfigs() != null) {
            kafkaConfig.getConnectConfigs().forEach((beanName, kafkaConnectConfig) -> {
                if (applicationContext instanceof GenericApplicationContext) {
                    ((GenericApplicationContext) applicationContext).registerBean(beanName,
                            EventListenerRegistry.class, () -> buildEventListenerRegistry(beanName, kafkaConnectConfig));
                    if (kafkaConnectConfig.getIsDefault()) {
                        String typeName = EventBusType.KAFKA.getTypeName();
                        ((GenericApplicationContext) applicationContext).registerBean(typeName,
                                EventListenerRegistry.class, () -> buildEventListenerRegistry(typeName, kafkaConnectConfig));
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
