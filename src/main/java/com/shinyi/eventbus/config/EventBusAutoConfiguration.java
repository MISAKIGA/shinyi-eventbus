package com.shinyi.eventbus.config;

import com.shinyi.eventbus.EventListenerRegistry;
import com.shinyi.eventbus.EventModel;
import com.shinyi.eventbus.config.rabbit.RabbitMqAutoConfiguration;
import com.shinyi.eventbus.config.rabbit.RabbitMqConfig;
import com.shinyi.eventbus.config.rocketmq.RocketMqAutoConfiguration;
import com.shinyi.eventbus.config.rocketmq.RocketMqConfig;
import com.shinyi.eventbus.registry.GuavaEventListenerRegistry;
import com.shinyi.eventbus.registry.SpringEventListenerRegistry;
import com.shinyi.eventbus.support.EventListenerRegistryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 事件总线组件自动配置类
 * @author MSG
 */
@Slf4j
@RequiredArgsConstructor
@Import({RocketMqConfig.class, RocketMqAutoConfiguration.class,
        RabbitMqConfig.class, RabbitMqAutoConfiguration.class})
public class EventBusAutoConfiguration implements InitializingBean {

    private final EventBusProperties eventBusProperties;

    @Override
    public void afterPropertiesSet() throws Exception { }

    @Bean
    public EventListenerRegistryManager eventListenerRegistryManager() {
        return new EventListenerRegistryManager();
    }

    @Bean(name = "guavaEventListenerRegistry")
    public EventListenerRegistry<?> guavaEventListenerRegistry(Executor eventBusExecutorService) {
        final GuavaEventListenerRegistry<EventModel<?>> eventListenerRegistry
                =  new GuavaEventListenerRegistry<>("guavaEventListenerRegistry");
        eventListenerRegistry.setThreadPool(eventBusExecutorService);
        return eventListenerRegistry;
    }

    @Bean(name = "springEventListenerRegistry")
    public EventListenerRegistry<?> springEventListenerRegistry(ApplicationContext applicationContext,
            Executor eventBusExecutorService) {
        final SpringEventListenerRegistry<EventModel<?>> eventListenerRegistry =
                new SpringEventListenerRegistry<>(applicationContext, "springEventListenerRegistry");
        eventListenerRegistry.setThreadPool(eventBusExecutorService);
        return eventListenerRegistry;
    }


    @Bean("eventBusExecutorService")
    public Executor getEventBusExecutorService() {
        return getAsyncExecutor();
    }

    private Executor getAsyncExecutor() {

        int corePoolSize = eventBusProperties.getThreadPoolCoreSize();
        int maxPoolSize = eventBusProperties.getThreadPoolMaxSize();
        int maxQueueSize = eventBusProperties.getMaxQueueSize();
        int awaitTerminationSeconds = eventBusProperties.getAwaitTerminationSeconds();
        String threadNamePrefix = eventBusProperties.getThreadNamePrefix();

        log.info("初始化异步线程池，threadNamePrefix: {},  corePoolSize: {}, maxPoolSize: {}, queueCapacity:{}, awaitTerminationSeconds:{}",
                threadNamePrefix,
                corePoolSize,
                maxPoolSize,
                maxQueueSize,
                awaitTerminationSeconds);

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        // 核心线程大小 默认区 CPU 数量
        taskExecutor.setCorePoolSize(corePoolSize);
        // 最大线程大小 默认区 CPU * 2 数量
        taskExecutor.setMaxPoolSize(maxPoolSize);
        // 队列最大容量
        taskExecutor.setQueueCapacity(maxQueueSize);
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        //---停机关闭线程池策略
        // 等待线程执行完在关闭
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        // 等待时间，默认 60s
        taskExecutor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        taskExecutor.setThreadNamePrefix(threadNamePrefix);
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 传入一个TaskDecorator的实现，在构造函数中，会将当前线程的Ttl快照复制一份，传入到子线程中
        // 可以考虑是否使用装饰器将线程ThreadLocal上下文获取进去
        //taskExecutor.setTaskDecorator(runnable -> TtlRunnable.get(runnable, true));
        taskExecutor.initialize();
        return taskExecutor;
    }
}
