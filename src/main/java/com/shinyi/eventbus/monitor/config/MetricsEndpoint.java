package com.shinyi.eventbus.monitor.config;

import com.shinyi.eventbus.monitor.MetricsCollector;
import com.shinyi.eventbus.monitor.MetricsSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 指标HTTP端点
 */
@RestController
@Configuration
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnProperty(name = "shinyi.eventbus.monitoring.http.enabled", havingValue = "true")
@RequestMapping("${shinyi.eventbus.monitoring.http.path:/metrics/eventbus}")
public class MetricsEndpoint {

    private final MetricsCollector collector;

    public MetricsEndpoint(MetricsCollector collector) {
        this.collector = collector;
    }

    @GetMapping
    public MetricsSnapshot metrics() {
        MetricsSnapshot snapshot = collector.getLastSnapshot();
        return snapshot != null ? snapshot : new MetricsSnapshot();
    }
}
