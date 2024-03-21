/*
 * Copyright 2022 LINE Corporation
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

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;

/**
 * A test configuration which retries to start the {@link Server} up to {@code 8} times.
 */
@AutoConfigureBefore(ArmeriaAutoConfiguration.class)
@Configuration
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(name = "armeria.server-enabled", havingValue = "true", matchIfMissing = true)
public class RetryableArmeriaServerGracefulShutdownLifecycleConfiguration {

    @Bean
    public ArmeriaServerSmartLifecycle smartLifecycle(Server server) {
        return new RetryableArmeriaServerGracefulShutdownLifecycle(server, 8);
    }
}
