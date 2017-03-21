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

package com.linecorp.armeria.it.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.logging.DropwizardMetricCollectingClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.DropwizardMetricCollectingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.test.AbstractServerTest;

public class DropwizardMetricsIntegrationTest extends AbstractServerTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    private CountDownLatch latch;

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.serviceAt("/helloservice", THttpService.of((Iface) name -> {
            if ("world".equals(name)) {
                return "success";
            }
            throw new IllegalArgumentException("bad argument");
        }).decorate((delegate, ctx, req) -> {
            ctx.log().addListener(log -> latch.countDown(),
                                  RequestLogAvailability.COMPLETE);
            return delegate.serve(ctx, req);
        }).decorate(DropwizardMetricCollectingService.newDecorator(
                metricRegistry, MetricRegistry.name("services"))));
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

        assertEquals(3, metricRegistry.getMeters()
                                      .get(clientMetricName("hello", "failures"))
                                      .getCount());
        assertEquals(3, metricRegistry.getMeters()
                                      .get(serverMetricName("hello", "failures"))
                                      .getCount());
        assertEquals(4, metricRegistry.getMeters()
                                      .get(clientMetricName("hello", "successes"))
                                      .getCount());
        assertEquals(4, metricRegistry.getMeters()
                                      .get(serverMetricName("hello", "successes"))
                                      .getCount());
        assertEquals(7, metricRegistry.getTimers()
                                      .get(clientMetricName("hello", "requests"))
                                      .getCount());
        assertEquals(7, metricRegistry.getTimers()
                                      .get(serverMetricName("hello", "requests"))
                                      .getCount());
        assertEquals(210, metricRegistry.getMeters()
                                      .get(clientMetricName("hello", "requestBytes"))
                                      .getCount());
        assertEquals(210, metricRegistry.getMeters()
                                      .get(serverMetricName("hello", "requestBytes"))
                                      .getCount());

        // Can't assert with exact byte count because the failure responses contain stack traces.
        assertThat(metricRegistry.getMeters()
                                 .get(clientMetricName("hello", "responseBytes"))
                                 .getCount()).isGreaterThan(0);
        assertThat(metricRegistry.getMeters()
                                 .get(serverMetricName("hello", "responseBytes"))
                                 .getCount()).isGreaterThan(0);
    }

    private static String serverMetricName(String method, String property) {
        return MetricRegistry.name("services", "/helloservice", method, property);
    }

    private static String clientMetricName(String method, String property) {
        return MetricRegistry.name("clients", "HelloService", method, property);
    }

    private void makeRequest(String name) {
        Iface client = new ClientBuilder("tbinary+" + uri("/helloservice"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           DropwizardMetricCollectingClient.newDecorator(
                                   metricRegistry, MetricRegistry.name("clients", "HelloService")))
                .decorator(RpcRequest.class, RpcResponse.class, CountDownClient::new)
                .build(Iface.class);
        try {
            client.hello(name);
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }

    private final class CountDownClient
            extends DecoratingClient<RpcRequest, RpcResponse, RpcRequest, RpcResponse> {

        CountDownClient(Client<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
            ctx.log().addListener(unused -> latch.countDown(), RequestLogAvailability.COMPLETE);
            return delegate().execute(ctx, req);
        }
    }
}
