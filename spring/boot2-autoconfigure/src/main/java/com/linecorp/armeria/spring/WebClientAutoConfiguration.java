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

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

/**
 * An auto-configuration for {@link WebClient}.
 */
@Configuration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(ArmeriaSettings.class)
public class WebClientAutoConfiguration {

    /**
     * Creates a {@link WebClientBuilder} bean.
     */
    @ConditionalOnMissingBean
    @Bean
    public Supplier<WebClientBuilder> internalWebClientBuilder(
            Optional<List<WebClientConfigurator>> webClientConfigurators) {
        return () -> {
            final WebClientBuilder builder = WebClient.builder();
            webClientConfigurators.ifPresent(cs -> cs.forEach(c -> c.configure(builder)));
            return builder;
        };
    }

    /**
     * Creates a {@link WebClientConfigurator} bean that applies the {@link ClientFactory}.
     */
    @Bean
    public WebClientConfigurator clientFactoryConfigurator(Optional<ClientFactory> clientFactory) {
        return builder -> builder.factory(clientFactory.orElseGet(ClientFactory::ofDefault));
    }

    /**
     * Creates a {@link WebClientConfigurator} bean that applies the {@code decorator}.
     */
    @Bean
    public WebClientConfigurator metricCollectingClientConfigurator(
            ArmeriaSettings armeriaSettings,
            Optional<MeterIdPrefixFunction> meterIdPrefixFunction) {
        return builder -> {
            if (armeriaSettings.isEnableMetrics()) {
                final MeterIdPrefixFunction function =
                        meterIdPrefixFunction.orElse(MeterIdPrefixFunction.ofDefault("armeria.client"));
                builder.decorator(MetricCollectingClient.newDecorator(function));
            }
        };
    }
}
