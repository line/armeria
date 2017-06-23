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
import static org.awaitility.Awaitility.await;

import org.junit.Rule;
import org.junit.Test;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.testing.server.ServerRule;

public class HttpHealthCheckedEndpointGroupTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private static class HealthCheckServerRule extends ServerRule {

        protected HealthCheckServerRule() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(HEALTH_CHECK_PATH, new HttpHealthCheckService());
        }
    }

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Rule
    public final ServerRule serverOne = new HealthCheckServerRule();

    @Rule
    public final ServerRule serverTwo = new HealthCheckServerRule();


    @Test
    public void endpoints() throws Exception {
        serverOne.start();
        serverTwo.start();
        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.httpPort()),
                        Endpoint.of("127.0.0.1", serverTwo.httpPort())),
                HEALTH_CHECK_PATH);
        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactly(
                        Endpoint.of("127.0.0.1", serverOne.httpPort()),
                        Endpoint.of("127.0.0.1", serverTwo.httpPort())));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.httpPort(),
                                           "127.0.0.1:" + serverTwo.httpPort()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of());

        serverTwo.stop().get();
        await().untilAsserted(
                () -> assertThat(
                        metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                        .isEqualTo(1));
    }

    @Test
    public void endpoints_containsUnhealthyServer() throws Exception {
        serverOne.start();
        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.httpPort()),
                        Endpoint.of("127.0.0.1", 2345)),
                HEALTH_CHECK_PATH);

        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints())
                        .containsOnly(Endpoint.of("127.0.0.1", serverOne.httpPort())));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(2);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(1);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.httpPort()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:2345"));
    }

    @Test
    public void endpoints_duplicateEntries() throws Exception {
        serverOne.start();
        HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", serverOne.httpPort()),
                        Endpoint.of("127.0.0.1", serverOne.httpPort()),
                        Endpoint.of("127.0.0.1", serverOne.httpPort())),
                HEALTH_CHECK_PATH);

        metricRegistry.registerAll(endpointGroup.newMetricSet("metric"));

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints())
                        .containsOnly(Endpoint.of("127.0.0.1", serverOne.httpPort())));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.all.count").getValue())
                .isEqualTo(3);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.count").getValue())
                .isEqualTo(3);
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.healthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of("127.0.0.1:" + serverOne.httpPort()));
        assertThat(metricRegistry.getGauges().get("endpointHealth.metric.unhealthy.endpoints").getValue())
                .isEqualTo(ImmutableSet.of());
    }
}
