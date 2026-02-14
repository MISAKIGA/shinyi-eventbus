package com.shinyi.eventbus.config.kafka;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

@Slf4j
@Data
@Order(Ordered.HIGHEST_PRECEDENCE)
@NoArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "shinyi.eventbus.kafka")
public class KafkaConfig implements InitializingBean {

    private Map<String, KafkaConnectConfig> connectConfigs;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (connectConfigs == null || connectConfigs.isEmpty()) {
            log.error("[EventBus] Kafka 配置不能为空.");
            return;
        }
        connectConfigs.forEach((k, v) -> {
            if (v.getBootstrapServers() == null || v.getBootstrapServers().isEmpty()) {
                throw new IllegalArgumentException("[EventBus] shinyi.eventbus.kafka.bootstrap-servers 不能为空");
            }
            log.info("[EventBus] Loaded Kafka Connect Config {}: {}", k, v);
        });
        log.info("[EventBus] Kafka 配置读取完成.");
    }
}
