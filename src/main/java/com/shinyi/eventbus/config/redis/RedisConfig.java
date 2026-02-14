package com.shinyi.eventbus.config.redis;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Redis configuration
 * @author MSGA
 */
@Slf4j
@Data
@Order(Ordered.HIGHEST_PRECEDENCE)
@NoArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "shinyi.eventbus.redis")
public class RedisConfig implements InitializingBean {

    private Map<String, RedisConnectConfig> connectConfigs;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (connectConfigs == null || connectConfigs.isEmpty()) {
            log.info("[EventBus] Redis configuration is empty, Redis event bus will not be enabled.");
            return;
        }
        connectConfigs.forEach((k, v) -> {
            if (v.getHost() == null || v.getHost().isEmpty()) {
                throw new IllegalArgumentException("[EventBus] shinyi.eventbus.redis.host cannot be empty");
            }
            log.info("[EventBus] Loaded Redis Connect Config {}: {}", k, v);
        });
        log.info("[EventBus] Redis configuration loaded.");
    }
}
