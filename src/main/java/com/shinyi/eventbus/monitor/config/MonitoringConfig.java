package com.shinyi.eventbus.monitor.config;

import com.shinyi.eventbus.monitor.ResetStrategy;
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

    /**
     * Reset strategy for metrics:
     * - NEVER: Never reset, cumulative only
     * - DAILY: Reset at midnight each day
     * - HOURLY: Reset at top of each hour
     * - INTERVAL: Reset every resetIntervalSeconds (default 24h)
     * - MANUAL: Only reset when explicitly called
     */
    private ResetStrategy resetStrategy = ResetStrategy.INTERVAL;

    /**
     * Interval in seconds for INTERVAL reset strategy
     */
    private long resetIntervalSeconds = 86400;

    /**
     * Time of day for DAILY reset in HH:mm format
     */
    private String dailyResetTime = "00:00";

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
