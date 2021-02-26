/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;

/**
 * Spring Boot {@code Configuration} that provides Armeria integration.
 */
@Configuration
@ConditionalOnBean(Server.class)
@ConditionalOnClass(ArmeriaBeanPostProcessor.class)
@AutoConfigureAfter(name = "com.linecorp.armeria.spring.ArmeriaAutoConfiguration")
public class ArmeriaBeanPostProcessorConfiguration {

    /**
     * Create an {@link ArmeriaBeanPostProcessor} bean.
     */
    @Bean
    @ConditionalOnMissingBean(ArmeriaBeanPostProcessor.class)
    public ArmeriaBeanPostProcessor armeriaBeanPostProcessor(BeanFactory beanFactory) {
        return new ArmeriaBeanPostProcessor(beanFactory);
    }
}
