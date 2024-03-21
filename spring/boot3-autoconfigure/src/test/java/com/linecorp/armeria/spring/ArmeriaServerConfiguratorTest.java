/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.linecorp.armeria.server.ServerBuilder;

@ExtendWith(SpringExtension.class)
class ArmeriaServerConfiguratorTest {

    @Inject
    List<ArmeriaServerConfigurator> configurators;

    @Test
    void ordering() {
        // We cannot check ArmeriaServerConfigurator#getOrder here because we didn't override it
        // so it always returns 0.
        assertThat(configurators.get(0).toString()).isEqualTo("order:-1");
        assertThat(configurators.get(1).toString()).isEqualTo("order:0");
        assertThat(configurators.get(2).toString()).isEqualTo("order:1");
    }

    @Configuration
    static class TestConfiguration {
        @Bean
        @Order(1)
        static ArmeriaServerConfigurator configurator1() {
            return new TestArmeriaServerConfigurator("order:1");
        }

        @Bean
        @Order(-1)
        static ArmeriaServerConfigurator configurator2() {
            return new TestArmeriaServerConfigurator("order:-1");
        }

        @Bean
        static ArmeriaServerConfigurator configurator3() {
            return new TestArmeriaServerConfigurator("order:0");
        }

        private static class TestArmeriaServerConfigurator implements ArmeriaServerConfigurator {
            private final String name;

            TestArmeriaServerConfigurator(String name) {
                this.name = name;
            }

            @Override
            public void configure(ServerBuilder serverBuilder) {}

            @Override
            public String toString() {
                return name;
            }
        }
    }
}
