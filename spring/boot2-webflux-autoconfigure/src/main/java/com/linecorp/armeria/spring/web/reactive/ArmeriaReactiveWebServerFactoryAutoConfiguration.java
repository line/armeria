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

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaSettings;

/**
 * An {@linkplain EnableAutoConfiguration auto-configuration} for a reactive web server.
 */
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(Server.class)
@EnableConfigurationProperties({ ServerProperties.class, ArmeriaSettings.class })
@Import(DataBufferFactoryWrapperConfiguration.class)
public class ArmeriaReactiveWebServerFactoryAutoConfiguration {

    /**
     * Returns a new {@link ArmeriaReactiveWebServerFactory} bean instance.
     */
    @Bean
    public ArmeriaReactiveWebServerFactory armeriaReactiveWebServerFactory(
            ConfigurableListableBeanFactory beanFactory, Environment environment) {
        return new ArmeriaReactiveWebServerFactory(beanFactory, environment);
    }
}
