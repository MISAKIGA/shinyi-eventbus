package com.shinyi.eventbus.monitor.config;

import com.shinyi.eventbus.monitor.Metrics;
import com.shinyi.eventbus.monitor.MetricsCollector;
import com.shinyi.eventbus.monitor.MetricsHolder;
import com.shinyi.eventbus.monitor.SimpleMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 监控自动配置
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shinyi.eventbus.monitoring.enabled", havingValue = "true")
@EnableConfigurationProperties(MonitoringConfig.class)
@Import(MetricsEndpoint.class)
public class MonitoringAutoConfiguration {

    private final MonitoringConfig config;
    private ScheduledExecutorService scheduler;

    @Bean
    public Metrics eventbusMetrics() {
        return new SimpleMetrics();
    }

    @Bean
    public MetricsCollector metricsCollector(Metrics metrics) {
        // 设置Metrics到Holder，供全局访问
        MetricsHolder.setMetrics(metrics);
        return new MetricsCollector(metrics, config.getIntervalSeconds() * 1000, config.getLog().isEnabled());
    }

    @Bean
    public ScheduledFuture<?> metricsScheduledTask(MetricsCollector collector) {
        scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "metrics-collector");
                t.setDaemon(true);
                return t;
            }
        );
        return scheduler.scheduleAtFixedRate(
            collector,
            config.getIntervalSeconds(),
            config.getIntervalSeconds(),
            TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
