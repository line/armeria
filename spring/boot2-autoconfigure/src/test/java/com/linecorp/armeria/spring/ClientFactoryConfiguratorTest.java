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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.linecorp.armeria.client.ClientFactoryBuilder;

@RunWith(SpringJUnit4ClassRunner.class)
public class ClientFactoryConfiguratorTest {

    @Configuration
    static class TestConfiguration {
        @Bean
        @Order(1)
        static ClientFactoryConfigurator configurator1() {
            return new TestClientFactoryConfigurator("order:1");
        }

        @Bean
        @Order(-1)
        static ClientFactoryConfigurator configurator2() {
            return new TestClientFactoryConfigurator("order:-1");
        }

        @Bean
        static ClientFactoryConfigurator configurator3() {
            return new TestClientFactoryConfigurator("order:0");
        }

        private static class TestClientFactoryConfigurator implements ClientFactoryConfigurator {
            private final String name;

            TestClientFactoryConfigurator(String name) {
                this.name = name;
            }

            @Override
            public void configure(ClientFactoryBuilder builder) {}

            @Override
            public String toString() {
                return name;
            }
        }
    }

    @Inject
    List<ClientFactoryConfigurator> configurators;

    @Test
    public void ordering() {
        assertThat(configurators.get(0).toString()).isEqualTo("order:-1");
        assertThat(configurators.get(1).toString()).isEqualTo("order:0");
        assertThat(configurators.get(2).toString()).isEqualTo("order:1");
    }
}
