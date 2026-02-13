package com.shinyi.eventbus.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
/**
 * 事件总线相关配置
 */
@Data
@ConfigurationProperties(prefix = "shinyi.eventbus")
public class EventBusProperties {

    protected static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private Integer threadPoolCoreSize = CPU_COUNT + 1;
    private Integer threadPoolMaxSize = CPU_COUNT * 4;
    private String threadNamePrefix = "eventbus-pool-";
    private Integer maxQueueSize = 10000;
    private Integer awaitTerminationSeconds = 60;
}
