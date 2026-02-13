package com.shinyi.eventbus.config.rabbit;

import cn.hutool.core.lang.Tuple;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.shinyi.eventbus.EventCallback;
import com.shinyi.eventbus.EventResult;

import java.io.IOException;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class AdvancedRabbitMqAsyncSender implements AutoCloseable {

    private final Channel channel;
    private final ExecutorService confirmExecutor;
    private final SortedMap<Long, Tuple> outstandingConfirms = Collections.synchronizedSortedMap(new TreeMap<>());

    public AdvancedRabbitMqAsyncSender(Channel channel) throws IOException {
        this.channel = channel;
        this.confirmExecutor = Executors.newSingleThreadExecutor();

        // 启用 Confirm 模式
        channel.confirmSelect();
        // 添加确认监听器
        channel.addConfirmListener(new ConfirmListener() {
            @Override
            public void handleAck(long deliveryTag, boolean multiple) {
                handleConfirm(deliveryTag, multiple, true);
            }

            @Override
            public void handleNack(long deliveryTag, boolean multiple) {
                handleConfirm(deliveryTag, multiple, false);
            }

            private void handleConfirm(long deliveryTag, boolean multiple, boolean ack) {
                confirmExecutor.submit(() -> {
                    SortedMap<Long, Tuple> confirmedMap = outstandingConfirms.headMap(deliveryTag + 1);
                    confirmedMap.forEach((seq, tuple) -> {
                        EventCallback callback = tuple.get(0);
                        String topic = tuple.get(1);
                        EventResult eventResult = new EventResult();
                        eventResult.setMessageId(String.valueOf(seq));
                        eventResult.setTopic(topic);
                        eventResult.setSourceResult(ack);
                        if (ack) {
                            callback.onSuccess(eventResult);
                        } else {
                            callback.onFailure(eventResult, new RuntimeException(
                                    "Broker nack message, seq=" + seq
                            ));
                        }
                    });
                    confirmedMap.clear();
                });
            }
        });
    }

    // 异步发送方法
    public void asyncPublishWithConfirm(
            String exchange,
            String routingKey,
            byte[] body,
            EventCallback callback
    ) throws IOException {
        long seqNo = channel.getNextPublishSeqNo();
        outstandingConfirms.put(seqNo, new Tuple(callback, exchange));
        channel.basicPublish(exchange, routingKey, null, body);
    }

    @Override
    public void close() throws IOException, TimeoutException {
        confirmExecutor.shutdown();
        channel.close();
    }
}
