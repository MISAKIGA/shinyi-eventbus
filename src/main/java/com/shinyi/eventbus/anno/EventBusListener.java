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
     *   EVENT 推荐 - 对称模式，只序列化/反序列化 entity（与 JSON 等效，语义更明确）
     *   JSON 将字符串用JSONUtil反序列化为entityType对象并传入到 EventModel.entity 字段
     *   RAW 直接发送 entity 原始字节，不进行 JSON 包装（高性能模式）
     *   MSG 直接将对应框架的消息对象传入到 EventModel.entity 字段
     *   BASIC 废弃 - 将字符串反序列化为entityType对象并传入到 EventModel.entity 字段
     *      String.class | Byte[].class | byte[].class
     *   DEFAULT 废弃 - 会将整个字符串序列化成 EventModel
     */
    SerializeType deserializeType() default SerializeType.EVENT;

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

    // --------------- EOS (Exactly-Once Semantics)

    /**
     * 启用 Exactly-Once Semantics (EOS) 语义。
     * 启用后:
     * - Producer 使用幂等生产者 (enable.idempotence=true)
     * - Consumer 使用手动提交 offset (enable.auto.commit=false)
     *
     * Default: false (向后兼容)
     */
    boolean exactlyOnce() default false;

    /**
     * 手动提交 offset 的批量大小。
     * 处理完这么多消息后提交一次 offset。
     * 值越大吞吐量越高，但失败时重复风险也越高。
     * Default: 100
     */
    int commitBatchSize() default 100;

    /**
     * 是否传递 entity 实体（true）还是完整的 EventModel（false）。
     *
     * true（默认）：将 entity 从 EventModel 中提取出来传递给监听器方法
     *   - 方法签名 List<String> → 收到 List<String>
     *   - 方法签名 List<MyEvent> → 收到 List<MyEvent>
     *
     * false：传递完整的 EventModel 给监听器方法
     *   - 方法签名 List<EventModel> → 收到 List<EventModel>
     *   - 用户可自行从 EventModel 获取 entity、eventId、group 等元数据
     *
     * Default: true（向后兼容）
     */
    boolean passEntity() default true;
}
