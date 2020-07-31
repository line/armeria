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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ConversionServiceFactoryBean;
import org.springframework.core.convert.ConversionService;

import com.google.common.collect.Sets;

/**
 * Provides useful {@link ConversionService}.
 */
@Configuration
public class ArmeriaSpringBoot1ConversionServiceConfiguration {

    /**
     * Create an {@link ConversionService} bean.
     */
    @Bean
    public ConversionService armeriaSpringBoot1ConversionService() {
        final ConversionServiceFactoryBean factoryBean = new ConversionServiceFactoryBean();
        factoryBean.setConverters(Sets.newHashSet(new StringToDurationConverter()));
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }
}
