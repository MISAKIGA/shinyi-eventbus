package com.shinyi.eventbus;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
import com.shinyi.eventbus.registry.GuavaEventListenerRegistry;
import com.shinyi.eventbus.registry.SpringEventListenerRegistry;
import com.shinyi.eventbus.support.EventListenerRegistryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.Mockito.when;

@SuppressWarnings("all")
@ExtendWith(MockitoExtension.class)
public class EventBusTest {

    @InjectMocks
    private EventListenerRegistryManager eventListenerRegistryManager;
    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private ApplicationEventMulticaster eventMulticaster;
//    @Mock
    private List<EventListener<EventModel<TestEvent>>> mockEventListenerRegistry = new ArrayList<>();


    @BeforeEach
    public void setUp() throws Exception {

        EventListener<EventModel<TestEvent>> testEventListener = new TestListener();
        mockEventListenerRegistry.add(testEventListener);

        when(applicationContext.getBean(ApplicationEventMulticaster.class))
                .thenReturn(eventMulticaster);

        List<EventListenerRegistry<?>> eventListenerRegistries = new ArrayList<>();
        SpringEventListenerRegistry<EventModel<TestEvent>> tSpringEventListenerRegistry = new SpringEventListenerRegistry<>(applicationContext, "spring");
        tSpringEventListenerRegistry.setThreadPool(ThreadUtil.newExecutor());
        tSpringEventListenerRegistry.initRegistryEventListener(mockEventListenerRegistry);
        eventListenerRegistries.add(tSpringEventListenerRegistry);
        GuavaEventListenerRegistry<EventModel<TestEvent>> gtaEventListenerRegistry = new GuavaEventListenerRegistry<>("guava");
        gtaEventListenerRegistry.setThreadPool(ThreadUtil.newExecutor());
        gtaEventListenerRegistry.initRegistryEventListener(mockEventListenerRegistry);
        eventListenerRegistries.add(gtaEventListenerRegistry);

        Map<String, EventListenerRegistry> regMap = eventListenerRegistries.stream()
                .collect(Collectors.toMap(e -> e.getEventBusType().getTypeName(), eventListenerRegistry -> eventListenerRegistry));

        eventListenerRegistryManager = new EventListenerRegistryManager();
        eventListenerRegistryManager.setApplicationContext(applicationContext);



        // 配置Mock对象的行为
        when(applicationContext.getBeansOfType(EventListenerRegistry.class))
                .thenReturn(regMap);

        when(applicationContext.getBeanNamesForType(Object.class, false, true))
                .thenReturn(new String[]{});

        eventListenerRegistryManager.start();
    }

//    @Test
//    public void publish() throws Exception{
//
//        long traceId = IdUtil.getSnowflakeNextId();
//        final TestEvent testEvent = new TestEvent();
//        testEvent.setFieldTest("hello");
//        testEvent.setFieldTest2("base guava event bus");
//        MDC.put("traceId", String.valueOf(traceId + 1));
//        long start = System.currentTimeMillis();
//        for (int i = 0; i < 10; i++) {
//            eventListenerRegistryManager.publish(EventBusType.GUAVA, EventModel.build(TestEvent.TOPIC_NAME, testEvent, true));
//        }
//        System.out.println("guava event bus cost time:" + (System.currentTimeMillis() - start));
//
//        TestEvent testEvent2 = new TestEvent();
//        testEvent2.setFieldTest("je");
//        testEvent2.setFieldTest2("base spring event bus");
//        start = System.currentTimeMillis();
//        for (int i = 0; i < 10; i++) {
//            eventListenerRegistryManager.publish(EventBusType.GUAVA, EventModel.build(TestEvent.TOPIC_NAME, testEvent2, true));
//        }
//        System.out.println("spring event bus cost time:" + (System.currentTimeMillis() - start));
//        Assert.isTrue(true);
////        org.wildfly.common.Assert.assertTrue(true);
//    }
}
