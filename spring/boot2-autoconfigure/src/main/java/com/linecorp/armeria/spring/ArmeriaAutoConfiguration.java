/*
 * Copyright 2017 LINE Corporation
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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfiguration.NonReactiveWebApplicationCondition;

/**
 * Spring Boot {@link Configuration} that provides Armeria integration.
 */
@Configuration
@Conditional(NonReactiveWebApplicationCondition.class)
@EnableConfigurationProperties(ArmeriaSettings.class)
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(name = "armeria.server-enabled", havingValue = "true", matchIfMissing = true)
public class ArmeriaAutoConfiguration extends AbstractArmeriaAutoConfiguration {

    /**
     * Condition for non-reactive web application type.
     */
    static class NonReactiveWebApplicationCondition extends NoneNestedConditions {

        NonReactiveWebApplicationCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        static class ReactiveWebApplication {}
    }
}
