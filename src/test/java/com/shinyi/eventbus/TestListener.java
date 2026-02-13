package com.shinyi.eventbus;

import com.shinyi.eventbus.listener.ExecutableEventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class TestListener extends ExecutableEventListener<TestEvent> {

    //@Qualifier("threadPoolTaskExecutor")
    //private final ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Override
    public String topic() {
        return TestEvent.TOPIC_NAME;
    }

    @Override
    protected void handle(List<EventModel<TestEvent>> messages) throws Throwable {
        for (EventModel<TestEvent> message : messages) {
            //System.out.println(threadPoolTaskExecutor);
            final TestEvent entity = message.getEntity();
            System.out.println(entity.getFieldTest() + ":" + entity.getFieldTest2());
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
