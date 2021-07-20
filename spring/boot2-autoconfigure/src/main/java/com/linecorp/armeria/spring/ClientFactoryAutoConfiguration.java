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

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;

/**
 * An auto-configuration for {@link ClientFactory}.
 */
@Configuration
@ConditionalOnClass(ClientFactory.class)
@AutoConfigureAfter(ClientFactoryMetricsAutoConfiguration.class)
public class ClientFactoryAutoConfiguration {

    /**
     * Creates a {@link ClientFactory} bean.
     */
    @ConditionalOnMissingBean
    @Bean
    public ClientFactory internalClientFactory(Optional<List<ClientFactoryConfigurator>> configurators) {
        if (configurators.isPresent()) {
            final ClientFactoryBuilder builder = ClientFactory.builder();
            configurators.get().forEach(c -> c.configure(builder));
            return builder.build();
        } else {
            return ClientFactory.ofDefault();
        }
    }
}
