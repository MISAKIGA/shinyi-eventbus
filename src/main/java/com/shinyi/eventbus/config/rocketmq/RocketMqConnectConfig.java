package com.shinyi.eventbus.config.rocketmq;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author MSGA
 */
@Slf4j
@NoArgsConstructor
@Data
public class RocketMqConnectConfig {

    /**
     * 是否为默认
     */
    private Boolean isDefault = Boolean.FALSE;
    /**
     * 后端类型 apache\aliyun，默认为 rocketmq
     */
    private String backendType = "aliyun";
    /**
     * 生产者-应用名称，rocketmq 后端使用，用于生成实例名称
     */
    private String producerAppName;
    /**
     * 生产者-topic，rocketmq 后端使用，用于生成实例名称
     */
    private String producerTopic;
    /**
     * 访问键
     */
    private String accessKey;
    /**
     * 访问密钥
     */
    private String secretKey;
    /**
     * ak/sk地址
     */
    private String namesrvAddr;
    /**
     * 分组ID
     */
    private String producerGroupId;
    /**
     * 跳过创建生产者（仅消费）
     */
    private boolean skipCreateProducer = false;
    /**
     * 消费者偏移量，earliest|latest|none
     */
    private String offset = "latest";
    /**
     * 配置消费者消费时间戳，offset=timestamp 时起效，格式 yyyyMMddHHmmss
     */
    private String consumeTimestamp;
    /**
     * 消费模式，orderly|concurrent
     */
    private String consumeMode = "concurrently";
    /**
     * 消息模型：clustering|broadcast
     */
    private String messageModel = "clustering";
    /**
     * 发送失败重试次数
     */
    private int retryTimesWhenSendFailed = 3;
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
    private int maxReconsumeTimes = 16;
    /**
     * 批量消费大小
     */
    private int pullBatchSize = 32;
    /**
     * 批量消费超时时间（毫米秒）
     */
    private int maxBatchConsumeWaitTime = 1;

    @Override
    public String toString() {
        return "RocketMqConnectConfig{" +
                "isDefault=" + isDefault +
                ", backendType='" + backendType + '\'' +
                ", producerAppName='" + producerAppName + '\'' +
                ", producerTopic='" + producerTopic + '\'' +
                ", namesrvAddr='" + namesrvAddr + '\'' +
                ", producerGroupId='" + producerGroupId + '\'' +
                ", skipCreateProducer=" + skipCreateProducer +
                ", offset='" + offset + '\'' +
                ", consumeTimestamp='" + consumeTimestamp + '\'' +
                ", consumeMode='" + consumeMode + '\'' +
                ", messageModel='" + messageModel + '\'' +
                ", retryTimesWhenSendFailed=" + retryTimesWhenSendFailed +
                ", sendMsgTimeout=" + sendMsgTimeout +
                ", consumerTimeoutMillis=" + consumerTimeoutMillis +
                ", maxReconsumeTimes=" + maxReconsumeTimes +
                ", pullBatchSize=" + pullBatchSize +
                ", maxBatchConsumeWaitTime=" + maxBatchConsumeWaitTime +
                '}';
    }
}
