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

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.FormatterRegistrar;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.format.support.FormattingConversionServiceFactoryBean;

import com.google.common.collect.Sets;

/**
 * Provides useful {@link ConversionService}.
 */
@Configuration
public class ArmeriaSpringBoot1ConversionServiceConfiguration {

    /**
     * Creates a new {@link ConversionService} bean.
     */
    @Bean
    @ConditionalOnMissingBean(ConversionService.class)
    public FormattingConversionService conversionService(ListableBeanFactory beanFactory) {
        final FormattingConversionServiceFactoryBean factoryBean = new FormattingConversionServiceFactoryBean();
        final FormatterRegistrar formatterRegistrar = new ArmeriaSpringBoot1FormatterRegistrar(beanFactory);
        factoryBean.setFormatterRegistrars(Sets.newHashSet(formatterRegistrar));
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    /**
     * Returns the {@link StringToDurationConverter} bean.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    public StringToDurationConverter armeriaSpringBoot1StringDurationConverter() {
        return new StringToDurationConverter();
    }
}
