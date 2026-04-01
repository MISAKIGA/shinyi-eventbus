package com.shinyi.eventbus.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 监控配置
 */
@Data
@ConfigurationProperties(prefix = "shinyi.eventbus.monitoring")
public class MonitoringConfig {
    private boolean enabled = false;
    private long intervalSeconds = 60;
    private LogConfig log = new LogConfig();
    private HttpConfig http = new HttpConfig();

    @Data
    public static class LogConfig {
        private boolean enabled = true;
    }

    @Data
    public static class HttpConfig {
        private boolean enabled = false;
        private int port = 8080;
        private String path = "/metrics/eventbus";
    }
}
