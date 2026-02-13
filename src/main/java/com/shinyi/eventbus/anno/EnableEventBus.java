package com.shinyi.eventbus.anno;


import com.shinyi.eventbus.config.EventBusAutoConfiguration;
import com.shinyi.eventbus.config.EventBusProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用事件总线
 * @author MSGA
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@EnableConfigurationProperties
@Import({EventBusProperties.class, EventBusAutoConfiguration.class})
//@ConditionalOnClass()
public @interface EnableEventBus {

}
