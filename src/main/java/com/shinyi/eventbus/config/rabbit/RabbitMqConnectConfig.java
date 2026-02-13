package com.shinyi.eventbus.config.rabbit;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author MSGA
 */
@Slf4j
@NoArgsConstructor
@Data
public class RabbitMqConnectConfig {

    /**
     * 是否为默认
     */
    private Boolean isDefault = Boolean.FALSE;
    /**
     * 后端类型
     */
    private String backendType = "";
    /**
     * 用户名
     */
    private String username;
    /**
     * 密码
     */
    private String password;
    /**
     * 服务主机地址
     */
    private String host;
    /**
     * 服务端口
     */
    private int port;
    /**
     * 交换机
     */
    private String exchange = "";
    /**
     * 交换类型：
     *   Direct 将消息中的RoutingKey与该Exchange关联的所有Binding中的BindingKey进行比较，如果相等，则发送到该Binding对应的Queue中
     *   Fanout 会将消息发送给所有与该  Exchange  定义过  Binding  的所有  Queues  中去，其实是一种广播行为。
     *   Topic 会按照正则表达式，对RoutingKey与BindingKey进行匹配，如果匹配成功，则发送到对应的Queue中。
     */
    private String exchangeType = "direct";
    /**
     * 虚拟路由
     */
    private String virtualHost = "/";
    /**
     * 是否持久化
     */
    private boolean durable = true;
    /**
     * 是否自动删除
     */
    private boolean autoDelete = false;
    /**
     * 队列
     */
    private String queue;
    /**
     * 路由key
     */
    private String routingKey = "";
    /**
     * 跳过创建生产者（仅消费）
     */
    private boolean skipCreateProducer = false;
    /**
     * 消费者偏移量，earliest|latest|none
     */
    //private String offset = "latest";
    /**
     * 配置消费者消费时间戳，offset=timestamp 时起效，格式 yyyyMMddHHmmss
     */
    //private String consumeTimestamp;
    /**
     * 消费模式=exchangeType
     */
    //private String consumeMode = "";
    /**
     * 消息模型：clustering|broadcast
     * =exchangeType
     */
    // private String messageModel = "";
    /**
     * 发送失败重试次数
     */
    //private int retryTimesWhenSendFailed = 3;
    /**
     * 发送消息超时时间（毫秒）
     */
    private int sendMsgTimeout = 3000;
    /**
     * 消费socket超时事件（毫秒）
     */
    private int consumerTimeoutMillis = 1000 * 10;
    /**
     * 最大重试次数
     */
    //private int maxReconsumeTimes = 16;
    /**
     * 批量消费大小
     */
    //private int pullBatchSize = 32;
    /**
     * 批量消费超时时间（毫米秒）
     */
    //private int maxBatchConsumeWaitTime = 1;

}
