/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.it.metric;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.codahale.metrics.Counting;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Sampling;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.metric.DropwizardMeterRegistries;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;

public class DropwizardMetricsIntegrationTest {

    private static final DropwizardMeterRegistry registry = DropwizardMeterRegistries.newRegistry();
    private static final MetricRegistry dropwizardRegistry = registry.getDropwizardRegistry();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.meterRegistry(registry);
            sb.service("/helloservice", THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            }).decorate(MetricCollectingService.newDecorator(
                    MeterIdPrefixFunction.ofDefault("armeria.server.HelloService"))));
        }
    };

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.close();
    }

    @Rule
    public final TestRule globalTimeout = new DisableOnDebug(new Timeout(30, TimeUnit.SECONDS));

    @Test
    public void normal() throws Exception {
        makeRequest("world");
        makeRequest("world");
        makeRequest("space");
        makeRequest("world");
        makeRequest("space");
        makeRequest("space");
        makeRequest("world");

        await().untilAsserted(() -> {
            assertThat(dropwizardRegistry.getMeters()
                                         .get(clientMetricNameWithStatusAndResult("requests", 200, "failure"))
                                         .getCount()).isEqualTo(3L);
            assertThat(dropwizardRegistry.getMeters()
                                         .get(serverMetricNameWithStatusAndResult("requests", 200, "failure"))
                                         .getCount()).isEqualTo(3L);
            assertThat(dropwizardRegistry.getMeters()
                                         .get(clientMetricNameWithStatusAndResult("requests", 200, "success"))
                                         .getCount()).isEqualTo(4L);
            assertThat(dropwizardRegistry.getMeters()
                                         .get(serverMetricNameWithStatusAndResult("requests", 200, "success"))
                                         .getCount()).isEqualTo(4L);

            assertTimer("requestDuration", 7);
            assertHistogram("requestLength", 7);
            assertTimer("responseDuration", 7);
            assertHistogram("responseLength", 7);
            assertTimer("totalDuration", 7);
        });
    }

    private static void assertHistogram(String property, int expectedCount) {
        assertSummary(dropwizardRegistry.getHistograms(), property, expectedCount);
    }

    private static void assertTimer(String property, int expectedCount) {
        assertSummary(dropwizardRegistry.getTimers(), property, expectedCount);
    }

    private static void assertSummary(Map<String, ?> map, String property, int expectedCount) {
        assertThat(((Counting) map.get(clientMetricNameWithStatus(property, 200))).getCount())
                .isEqualTo(expectedCount);
        assertThat(((Sampling) map.get(clientMetricNameWithStatus(property, 200))).getSnapshot().getMean())
                .isPositive();
        assertThat(((Counting) map.get(serverMetricNameWithStatus(property, 200))).getCount())
                .isEqualTo(expectedCount);
        assertThat(((Sampling) map.get(serverMetricNameWithStatus(property, 200))).getSnapshot().getMean())
                .isPositive();
    }

    private static String serverMetricName(String property, int status) {
        return MetricRegistry.name("armeria", "server", "HelloService", property,
                                   "hostnamePattern:*", "httpStatus:" + status,
                                   "method:hello", "pathMapping:exact:/helloservice");
    }

    private static String serverMetricNameWithStatus(String property, int status) {
        return serverMetricName(property, status);
    }

    private static String serverMetricNameWithStatusAndResult(String property, int status, String result) {
        return serverMetricName(property, status) + ".result:" + result;
    }

    private static String clientMetricName(String property, int status) {
        return MetricRegistry.name("armeria", "client", "HelloService", property,
                                   "httpStatus:" + status, "method:hello");
    }

    private static String clientMetricNameWithStatus(String prop, int status) {
        return clientMetricName(prop, status);
    }

    private static String clientMetricNameWithStatusAndResult(String prop, int status, String result) {
        return clientMetricName(prop, status) + ".result:" + result;
    }

    private static void makeRequest(String name) {
        final Iface client = new ClientBuilder(server.uri(BINARY, "/helloservice"))
                .factory(clientFactory)
                .decorator(RpcRequest.class, RpcResponse.class,
                           MetricCollectingClient.newDecorator(
                                   MeterIdPrefixFunction.ofDefault("armeria.client.HelloService")))
                .build(Iface.class);
        try {
            client.hello(name);
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
