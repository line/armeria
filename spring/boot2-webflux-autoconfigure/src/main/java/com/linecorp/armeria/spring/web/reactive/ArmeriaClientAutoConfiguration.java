/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;

import com.linecorp.armeria.spring.WebClientConfigurator;

/**
 * An auto-configuration for Armeria-based {@link WebClient}.
 */
@Configuration
@ConditionalOnClass(WebClient.Builder.class)
@ConditionalOnMissingBean(ClientHttpConnector.class)
@Import({ WebClientAutoConfiguration.class, DataBufferFactoryWrapperConfiguration.class })
public class ArmeriaClientAutoConfiguration {

    /**
     * Returns a {@link ClientHttpConnector} which is configured by a list of
     * {@link ArmeriaClientConfigurator}s.
     */
    @Bean
    public ClientHttpConnector clientHttpConnector(
            List<WebClientConfigurator> customizer,
            DataBufferFactoryWrapper<?> factoryWrapper) {
        return new ArmeriaClientHttpConnector(customizer, factoryWrapper);
    }

    /**
     * Returns a {@link WebClientCustomizer} which sets an {@link ArmeriaClientHttpConnector} to the
     * {@link Builder}.
     */
    @Bean
    public WebClientCustomizer webClientCustomizer(ClientHttpConnector clientHttpConnector) {
        return builder -> builder.clientConnector(clientHttpConnector);
    }
}
