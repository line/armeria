/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureServerWithArmeriaSettings;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.InternalServices;

@SpringBootTest(classes = ArmeriaConfigurationUtilTest.TestConfiguration.class)
@ActiveProfiles({ "local", "internalPortEqualToManagementPortTest" })
@Timeout(10)
class ArmeriaConfigurationUtilTest {

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        public Server armeriaServer() {
            return Server.builder().service("/test", (ctx, req) -> HttpResponse.of(HttpStatus.OK)).build();
        }
    }

    @Inject
    private ArmeriaSettings armeriaSettings;

    @Inject
    InternalServices internalServices;

    @Inject
    private BeanFactory beanFactory;

    @Test
    void testInternalServicePortEqualToManagementServerPort() {
        assertThatThrownBy(() ->
                                   configureServerWithArmeriaSettings(
                                           Server.builder(), armeriaSettings, internalServices,
                                           ImmutableList.of(), ImmutableList.of(),
                                           Flags.meterRegistry(),
                                           MeterIdPrefixFunction.ofDefault("armeria.server"),
                                           ImmutableList.of(), ImmutableList.of(), beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Internal service port cannot be equal to the management server port.");
    }
}
