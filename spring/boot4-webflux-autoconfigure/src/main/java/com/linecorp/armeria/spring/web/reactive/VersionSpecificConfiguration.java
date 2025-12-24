/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.reactive.ReactiveWebServerConfiguration;
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient.Builder;

class VersionSpecificConfiguration {

    @Configuration
    @Import(WebClientAutoConfiguration.class)
    static class WebClientConfiguration {
        /**
         * Returns a {@link WebClientCustomizer} which sets an {@link ArmeriaClientHttpConnector} to the
         * {@link Builder}.
         */
        @Bean
        public WebClientCustomizer webClientCustomizer(ClientHttpConnector clientHttpConnector) {
            return builder -> builder.clientConnector(clientHttpConnector);
        }
    }

    @Configuration
    @Import(ReactiveWebServerConfiguration.class)
    @EnableConfigurationProperties({ ServerProperties.class })
    static class WebServerConfiguration {

        @Bean
        static ManagementContextFactory reactiveWebChildContextFactory() {
            return new ManagementContextFactory(WebApplicationType.REACTIVE, ReactiveWebServerFactory.class);
        }
    }
}
