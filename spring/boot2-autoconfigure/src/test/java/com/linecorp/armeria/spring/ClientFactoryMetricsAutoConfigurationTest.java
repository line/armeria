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

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.spring.ClientFactoryMetricsAutoConfigurationTest.TestConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class ClientFactoryMetricsAutoConfigurationTest {

    @SpringBootApplication
    public static class TestConfiguration {

        @Primary
        @Bean
        public MeterRegistry meterRegistry() {
            return PrometheusMeterRegistries.defaultRegistry();
        }
    }

    @Inject
    private ClientFactory clientFactory;

    @Test
    public void testCustomMeterRegistry() {
        assertThat(clientFactory.meterRegistry()).isInstanceOf(PrometheusMeterRegistry.class);
    }
}
