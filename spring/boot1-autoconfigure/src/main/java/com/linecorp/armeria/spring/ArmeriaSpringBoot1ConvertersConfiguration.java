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

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ConversionServiceFactoryBean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;

import com.google.common.collect.Sets;

/**
 * Provides useful {@link Converter}s.
 */
@Configuration
public class ArmeriaSpringBoot1ConvertersConfiguration {

    /**
     * Create an {@link ConversionService} bean.
     */
    @Bean
    @ConditionalOnMissingBean(ConversionService.class)
    public ConversionServiceFactoryBean conversionService(List<Converter> converterList) {
        final ConversionServiceFactoryBean conversionServiceFactoryBean = new ConversionServiceFactoryBean();
        conversionServiceFactoryBean.setConverters(Sets.newHashSet(converterList));
        return conversionServiceFactoryBean;
    }

    /**
     * Create an {@link StringToDurationConverter} bean.
     */
    @Bean
    public StringToDurationConverter stringToDurationConverter() {
        return new StringToDurationConverter();
    }
}
