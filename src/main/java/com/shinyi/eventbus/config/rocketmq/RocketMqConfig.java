package com.shinyi.eventbus.config.rocketmq;

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
 * @author MSGA
 */
@Slf4j
@Data
@Order(Ordered.HIGHEST_PRECEDENCE)
@NoArgsConstructor
@Configuration
//@ConditionalOnProperty(prefix = "shinyi.eventbus.rocket-mq", name = "connect-configs", matchIfMissing = false)
@ConfigurationProperties(prefix = "shinyi.eventbus.rocket-mq")
public class RocketMqConfig implements InitializingBean {

    private Map<String, RocketMqConnectConfig> connectConfigs;

    /**
     * 初始化方法：检查配置是否合法
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        if (connectConfigs == null || connectConfigs.isEmpty()) {
            log.error("[EventBus] RocketMQ 配置不能为空.");
            return;
        }
        connectConfigs.forEach((k, v)->{
            if(v.getNamesrvAddr() == null || v.getNamesrvAddr().isEmpty()) {
                throw new IllegalArgumentException("[EventBus] shinyi.eventbus.rocket-mq.namesrvAddr 不能为空");
            }
            log.info("[EventBus] Loaded RocketMQ Connect Config {}: {}", k, v);
        });
        // 如果有需要，可以在这里执行其他初始化逻辑
        log.info("[EventBus] RocketMQ 配置读取完成.");
    }
}
