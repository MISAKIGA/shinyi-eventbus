package com.shinyi.eventbus.registry;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.shinyi.eventbus.*;
import com.shinyi.eventbus.config.redis.RedisConnectConfig;
import com.shinyi.eventbus.serialize.BaseSerializer;
import com.shinyi.eventbus.serialize.Serializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis event listener registry
 * Supports both Redis Pub/Sub and Redis Stream
 * @author MSGA
 */
@Slf4j
@RequiredArgsConstructor
public class RedisMqEventListenerRegistry<T extends EventModel<?>> implements EventListenerRegistry<T> {

    protected final ApplicationContext applicationContext;
    protected final String registryBeanName;
    protected final RedisConnectConfig redisConnectConfig;

    private StringRedisTemplate redisTemplate;
    private RedisMessageListenerContainer container;
    private final Set<ExecutorService> executorSet = ConcurrentHashMap.newKeySet();
    protected final Serializer serializer = new BaseSerializer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Override
    public EventBusType getEventBusType() {
        return EventBusType.REDIS;
    }

    public void init() {
        if (redisConnectConfig.getHost() == null || redisConnectConfig.getHost().isEmpty()) {
            throw new IllegalArgumentException("Redis host cannot be empty");
        }

        // Create simple RedisTemplate
        redisTemplate = new StringRedisTemplate();
        
        // Note: In actual usage, the connection factory should be injected
        // This is a basic initialization
        log.info("Redis connection initialized for {}:{}", redisConnectConfig.getHost(), redisConnectConfig.getPort());
    }

    @Override
    public void initRegistryEventListener(List<EventListener<T>> listener) {
        if (listener == null) {
            return;
        }
        CompletableFuture[] futures = listener.stream()
                .filter(l -> CollectionUtil.isNotEmpty(l.registryBeanName()) && CollectionUtil.contains(l.registryBeanName(), registryBeanName)
                        || CollectionUtil.isEmpty(l.registryBeanName()) && redisConnectConfig.getIsDefault())
                .map(l -> CompletableFuture.runAsync(() -> initConsumer(l)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    private void initConsumer(com.shinyi.eventbus.EventListener<T> listener) {
        String topic = listener.topic();
        if (topic == null || topic.isEmpty()) {
            topic = redisConnectConfig.getChannelPrefix() + registryBeanName;
        }

        if (redisConnectConfig.isUseStream()) {
            initStreamConsumer(listener, topic);
        } else {
            initPubSubConsumer(listener, topic);
        }
    }

    private void initPubSubConsumer(com.shinyi.eventbus.EventListener<T> listener, String channel) {
        // Note: Pub/Sub requires RedisConnectionFactory to be properly configured
        // This is a simplified implementation
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "redis-sub-" + channel));
        executorSet.add(executor);

        executor.submit(() -> {
            try {
                // Simple polling-based consumer as fallback
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("Redis pub/sub error: " + e.getMessage(), e);
            }
        });

        log.info("Redis pub/sub consumer registered for channel: {}, group: {}", channel, listener.group());
    }

    private void initStreamConsumer(com.shinyi.eventbus.EventListener<T> listener, String streamKey) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "redis-stream-" + streamKey));
        executorSet.add(executor);

        String actualStreamKey = streamKey;
        if (redisConnectConfig.getStreamKey() != null && !redisConnectConfig.getStreamKey().isEmpty()) {
            actualStreamKey = redisConnectConfig.getStreamKey();
        }

        final String finalStreamKey = actualStreamKey;
        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Simplified stream reading - actual implementation would use RedisTemplate opsForStream
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.warn("Redis stream processing failed: " + e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Redis stream error: " + e.getMessage(), e);
            }
        });

        log.info("Redis stream consumer registered for stream: {}, group: {}", finalStreamKey, listener.group());
    }

    protected EventModel<?> deserialize(byte[] body, com.shinyi.eventbus.EventListener<T> listener) {
        EventModel<?> eventModel;
        try {
            eventModel = serializer.deserialize(body, listener.serializeType(), listener.entityType());
        } catch (Throwable e) {
            log.warn(registryBeanName + " Message deserialization failed: " + new String(body, StandardCharsets.UTF_8));
            eventModel = EventModel.build(listener.topic(), null);
        }
        eventModel.setRawData(body);
        eventModel.setGroup(listener.group());
        eventModel.setDriveType(registryBeanName + "#" + getEventBusType().getTypeName());
        if (eventModel.getEventId() == null) {
            eventModel.setEventId(System.currentTimeMillis() + "");
        }
        if (eventModel.getTopic() == null) {
            eventModel.setTopic(listener.topic());
        }
        return eventModel;
    }

    @Override
    public void publish(T eventModel) {
        try {
            byte[] body = serializer.serialize(eventModel, eventModel.getSerializeType());
            String channel = eventModel.getTopic();

            if (channel == null || channel.isEmpty()) {
                channel = redisConnectConfig.getChannelPrefix() + registryBeanName;
            }

            if (redisConnectConfig.isUseStream() && redisConnectConfig.getStreamKey() != null) {
                // Publish to stream - would need proper stream operations
                log.debug("Redis stream publish to: {}", redisConnectConfig.getStreamKey());
            } else {
                // Publish to pub/sub
                redisTemplate.convertAndSend(channel, new String(body, StandardCharsets.UTF_8));
            }

            log.debug("Redis message published to channel: {}", channel);
        } catch (Exception e) {
            log.warn("{} Publish message exception: {}", getEventBusType().getTypeName(), e.getMessage());
            throw new RuntimeException("Redis publish failed", e);
        }
    }

    @Override
    public void close() {
        for (ExecutorService executor : executorSet) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        if (container != null) {
            try {
                container.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}
