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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.format.support.FormattingConversionServiceFactoryBean;

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
    @ConditionalOnMissingBean(ConversionService.class)
    public FormattingConversionService conversionService() {
        final FormattingConversionServiceFactoryBean factoryBean = new FormattingConversionServiceFactoryBean();
        factoryBean.setFormatterRegistrars(Sets.newHashSet(new ArmeriaSpringBoot1FormatterRegistrar()));
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    /**
     * Create an {@link StringToDurationConverter} bean. If {@link ConversionService} is already registered,
     * it is provided so that {@link Converter} can be taken out from {@link BeanFactory} and used.
     */
    @Bean
    public StringToDurationConverter armeriaSpringBoot1StringDurationConverter() {
        return new StringToDurationConverter();
    }
}
