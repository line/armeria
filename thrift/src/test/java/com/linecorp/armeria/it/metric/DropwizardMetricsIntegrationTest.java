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

package com.linecorp.armeria.it.metric;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.DropwizardMetricsExporter;
import com.linecorp.armeria.common.metric.MetricKeyFunction;
import com.linecorp.armeria.common.metric.MetricsExporter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

public class DropwizardMetricsIntegrationTest {

    private static final MetricRegistry metricRegistry = new MetricRegistry();
    private static final MetricsExporter serverMetricsExporter =
            new DropwizardMetricsExporter(metricRegistry, "server");
    private static final MetricsExporter clientMetricsExporter =
            new DropwizardMetricsExporter(metricRegistry, "client");

    private static CountDownLatch latch;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/helloservice", THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            }).decorate((delegate, ctx, req) -> {
                ctx.log().addListener(log -> latch.countDown(),
                                      RequestLogAvailability.COMPLETE);
                return delegate.serve(ctx, req);
            }).decorate(MetricCollectingService.newDecorator(MetricKeyFunction.ofLabellessDefault("request"))));
        }
    };

    @BeforeClass
    public static void addExporter() {
        server.server().metrics().addExporter(serverMetricsExporter);
        ClientFactory.DEFAULT.metrics().addExporter(clientMetricsExporter);
    }

    @AfterClass
    public static void removeExporter() {
        server.server().metrics().removeExporter(serverMetricsExporter);
        ClientFactory.DEFAULT.metrics().removeExporter(clientMetricsExporter);
    }

    @Test(timeout = 10000L)
    public void normal() throws Exception {
        latch = new CountDownLatch(14);

        makeRequest("world");
        makeRequest("world");
        makeRequest("space");
        makeRequest("world");
        makeRequest("space");
        makeRequest("space");
        makeRequest("world");

        // Wait until all RequestLogs are collected.
        latch.await();

        assertThat(metricRegistry.getGauges()
                                 .get(clientMetricName("hello", "failure"))
                                 .getValue()).isEqualTo(3L);
        assertThat(metricRegistry.getGauges()
                                 .get(serverMetricName("hello", "failure"))
                                 .getValue()).isEqualTo(3L);
        assertThat(metricRegistry.getGauges()
                                 .get(clientMetricName("hello", "success"))
                                 .getValue()).isEqualTo(4L);
        assertThat(metricRegistry.getGauges()
                                 .get(serverMetricName("hello", "success"))
                                 .getValue()).isEqualTo(4L);
        assertThat(metricRegistry.getGauges()
                                 .get(clientMetricName("hello", "total"))
                                 .getValue()).isEqualTo(7L);
        assertThat(metricRegistry.getGauges()
                                 .get(serverMetricName("hello", "total"))
                                 .getValue()).isEqualTo(7L);

        assertHistogram("hello", "requestDuration", 7);
        assertHistogram("hello", "requestLength", 7);
        assertHistogram("hello", "responseDuration", 7);
        assertHistogram("hello", "responseLength", 7);
        assertHistogram("hello", "totalDuration", 7);
    }

    private static void assertHistogram(String method, String property, int expectedCount) {
        assertThat(metricRegistry.getHistograms()
                                 .get(clientMetricName(method, property))
                                 .getCount()).isEqualTo(expectedCount);
        assertThat(metricRegistry.getHistograms()
                                 .get(clientMetricName(method, property))
                                 .getSnapshot().getMean()).isPositive();
        assertThat(metricRegistry.getHistograms()
                                 .get(serverMetricName(method, property))
                                 .getCount()).isEqualTo(expectedCount);
        assertThat(metricRegistry.getHistograms()
                                 .get(serverMetricName(method, property))
                                 .getSnapshot().getMean()).isPositive();
    }

    private static String serverMetricName(String method, String property) {
        return MetricRegistry.name("server", "request", "exact:/helloservice", method, property);
    }

    private static String clientMetricName(String method, String property) {
        return MetricRegistry.name("client", "request", "HelloService", method, property);
    }

    private static void makeRequest(String name) {
        Iface client = new ClientBuilder(server.uri(BINARY, "/helloservice"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           (delegate, ctx, req) -> {
                               ctx.log().addListener(unused -> latch.countDown(),
                                                     RequestLogAvailability.COMPLETE);
                               return delegate.execute(ctx, req);
                           })
                .decorator(RpcRequest.class, RpcResponse.class,
                           MetricCollectingClient.newDecorator(
                                   MetricKeyFunction.ofLabellessDefault("request", "HelloService")))
                .build(Iface.class);
        try {
            client.hello(name);
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
