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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.linecorp.armeria.client.HttpClientBuilder;

@RunWith(SpringJUnit4ClassRunner.class)
public class ArmeriaClientConfiguratorTest {

    @Configuration
    static class TestConfiguration {
        @Bean
        @Order(1)
        static ArmeriaClientConfigurator configurator1() {
            return new TestArmeriaClientConfigurator("order:1");
        }

        @Bean
        @Order(-1)
        static ArmeriaClientConfigurator configurator2() {
            return new TestArmeriaClientConfigurator("order:-1");
        }

        @Bean
        static ArmeriaClientConfigurator configurator3() {
            return new TestArmeriaClientConfigurator("order:0");
        }

        private static class TestArmeriaClientConfigurator implements ArmeriaClientConfigurator {
            private final String name;

            TestArmeriaClientConfigurator(String name) {
                this.name = name;
            }

            @Override
            public void configure(HttpClientBuilder builder) {}

            @Override
            public String toString() {
                return name;
            }
        }
    }

    @Inject
    List<ArmeriaClientConfigurator> configurators;

    @Test
    public void ordering() {
        // We cannot check ArmeriaClientConfigurator#getOrder here because we didn't override it
        // so it always returns 0.
        assertThat(configurators.get(0).toString()).isEqualTo("order:-1");
        assertThat(configurators.get(1).toString()).isEqualTo("order:0");
        assertThat(configurators.get(2).toString()).isEqualTo("order:1");
    }
}
