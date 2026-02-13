package com.shinyi.eventbus.anno;

import com.shinyi.eventbus.SerializeType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 事件总线监听
 * @author MSGA
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventBusListener {

    /**
     * 事件注册中心实例 Bean（注册在 Spring 容器中的 EventListenerRegistry 的 Bean 名称 eventbus.connect-configs.xxx）
     */
    String[] name() default {};

    /**
     * 当前监听器的 BeanName，默认会自动生成
     */
    String beanName() default "";

    /**
     * topic
     *   Rabbit 会合并 group.topic 作为 queue 以确保互斥消费
     */
    String topic();

    /**
     * 事件实体类型,通常使用外部中间件通信序列化时需要传
     */
    Class<?> entityType() default Void.class;

    /**
     * 反序列化类型：
     *   DEFAULT 默认,会将整个字符串序列化成 EventModel
     *   BASIC 将字符串反序列化为entityType对象并传入到 EventModel.entity 字段
     *      String.class | Byte[].class | byte[].class
     *   JSON 将字符串用JSONUtil反序列化为entityType对象并传入到 EventModel.entity 字段
     *   MSG 直接将对应框架的消息对象传入到 EventModel.entity 字段
     */
    SerializeType deserializeType() default SerializeType.DEFAULT;

    /**
     * group
     *   Rabbit 会合并 group.topic 作为 queue 以确保互斥消费
     */
    String group() default "DEFAULT";

    /**
     * 优先级比较高，会覆盖配置文件的 offset。
     */
    String offset() default "";

    /**
     * tags (RocketMQ Filter Tags)
     */
    String tags() default "";

    /**
     * 消费模式 PUSH OR PULL
     */
    String consumerMode() default "PUSH";

    /**
     * 应用名称
     */
    String appName() default "";

    // --------------- rabbitMQ
    /**
     * Rabbit 队列
     */
    String queue() default "";
    /**
     * Rabbit 交换机
     */
    String exchange() default "";
    /**
     * Rabbit 交换机类型
     */
    String exchangeType() default "direct";
    /**
     * Rabbit 路由Key
     */
    String routingKey() default "";
    /**
     * Rabbit 是否持久化
     */
    boolean durable() default true;
    /**
     * Rabbit 消费消息后是否删除
     */
    boolean autoDelete() default false;
}
