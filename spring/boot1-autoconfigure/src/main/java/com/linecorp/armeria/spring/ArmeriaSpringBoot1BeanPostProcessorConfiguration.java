package com.linecorp.armeria.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;

/**
 * Spring Boot {@link Configuration} that provides Armeria integration.
 */
@Configuration
@ConditionalOnBean(Server.class)
@ConditionalOnClass(ArmeriaSpringBoot1BeanPostProcessor.class)
public class ArmeriaSpringBoot1BeanPostProcessorConfiguration {

    /**
     * Create a {@link ArmeriaSpringBoot1BeanPostProcessor} bean.
     */
    @Bean
    @ConditionalOnMissingBean(ArmeriaSpringBoot1BeanPostProcessor.class)
    public ArmeriaSpringBoot1BeanPostProcessor armeriaSpringBoot1BeanPostProcessor(BeanFactory beanFactory) {
        return new ArmeriaSpringBoot1BeanPostProcessor(beanFactory);
    }
}
