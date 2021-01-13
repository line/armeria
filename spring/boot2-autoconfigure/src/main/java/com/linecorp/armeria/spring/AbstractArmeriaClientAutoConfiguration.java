/*
 * Copyright 2021 LINE Corporation
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

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.linecorp.armeria.client.ClientFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Abstract class for implementing ArmeriaClientAutoConfiguration of Spring Boot autoconfigure modules.
 */
public abstract class AbstractArmeriaClientAutoConfiguration {

    /**
     * Create a {@link ClientFactory} bean with {@link MeterRegistry} applied.
     */
    @Bean
    @ConditionalOnMissingBean(ClientFactory.class)
    public ClientFactory clientFactory(Optional<MeterRegistry> registry) {
        return ClientFactory.builder().meterRegistry(registry.orElse(Metrics.globalRegistry)).build();
    }

    /**
     * Create an {@link ArmeriaClientConfigurator} bean that applies the {@link ClientFactory}.
     */
    @Bean
    @ConditionalOnBean(ClientFactory.class)
    public ArmeriaClientConfigurator clientConfigurator(ClientFactory factory) {
        return builder -> builder.factory(factory);
    }
}
