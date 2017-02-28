/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.test.AbstractServiceServer;

public class HttpHealthCheckedEndpointGroupTest {

    private static class ServiceServer extends AbstractServiceServer {
        private final String healthCheckPath;

        ServiceServer(String healthCheckPath) {
            this.healthCheckPath = healthCheckPath;
        }

        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            sb.serviceAt(healthCheckPath, new HttpHealthCheckService());
        }
    }

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Test
    public void endpoints() throws Exception {
        String healthCheckPath = "/healthcheck";
        ServiceServer serverOne = new ServiceServer(healthCheckPath).start();
        ServiceServer serverTwo = new ServiceServer(healthCheckPath).start();
        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.port()),
                        Endpoint.of("127.0.0.1", serverTwo.port())),
                healthCheckPath);
        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));

        Thread.sleep(4000); // Wait until updating server list.
        assertThat(endpointGroup.endpoints()).containsExactly(
                Endpoint.of("127.0.0.1", serverOne.port()),
                Endpoint.of("127.0.0.1", serverTwo.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.port(), "127.0.0.1:" + serverTwo.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of());
        serverOne.close();
        serverTwo.close();
    }

    @Test
    public void endpoints_containsUnhealthyServer() throws Exception {
        String healthCheckPath = "/healthcheck";
        ServiceServer serverOne = new ServiceServer(healthCheckPath).start();

        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.port()),
                        Endpoint.of("127.0.0.1", 2345)),
                healthCheckPath);
        Thread.sleep(4000); // Wait until updating server list.

        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));
        assertThat(endpointGroup.endpoints()).containsOnly(Endpoint.of("127.0.0.1", serverOne.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(1);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:2345"));
        serverOne.close();
    }

    @Test
    public void endpoints_duplicatedEntry() throws Exception {
        String healthCheckPath = "/healthcheck";
        ServiceServer serverOne = new ServiceServer(healthCheckPath).start();

        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.port()),
                        Endpoint.of("127.0.0.1", serverOne.port()),
                        Endpoint.of("127.0.0.1", serverOne.port())),
                healthCheckPath);
        Thread.sleep(4000); // Wait until updating server list.

        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));
        assertThat(endpointGroup.endpoints()).containsOnly(Endpoint.of("127.0.0.1", serverOne.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(3);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(3);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.port()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of());
        serverOne.close();
    }
}
