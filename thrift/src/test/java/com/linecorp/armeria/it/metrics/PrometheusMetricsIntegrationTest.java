/**
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.it.metrics;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.apache.thrift.TException;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.logging.MetricLabel;
import com.linecorp.armeria.common.logging.PrometheusMetricRequestDecorator;
import com.linecorp.armeria.common.logging.PrometheusRegistryWrapper;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.metric.PrometheusExporterHttpService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

public class PrometheusMetricsIntegrationTest {
    private static final PrometheusRegistryWrapper collectorRegistry = new PrometheusRegistryWrapper();

    private static volatile CountDownLatch latch;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceAt("/thrift", THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            }).decorate((delegate, ctx, req) -> {
                ctx.log().addListener(log -> latch.countDown(),
                                      RequestLogAvailability.COMPLETE);
                return delegate.serve(ctx, req);
            }).decorate(
                    PrometheusMetricRequestDecorator
                            .decorateService(collectorRegistry,
                                             log -> defaultMetricName(log, "HelloService",
                                                                      serviceMetricName()))))
              .serviceAt("/internal/prometheus/metrics-v2",
                         new PrometheusExporterHttpService(collectorRegistry));
        }
    };

    private static void makeRequest(String name) throws TException {
        Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           (delegate, ctx, req) -> {
                               ctx.log().addListener(unused -> latch.countDown(),
                                                     RequestLogAvailability.COMPLETE);
                               return delegate.execute(ctx, req);
                           })
                .decorator(RpcRequest.class, RpcResponse.class,
                           PrometheusMetricRequestDecorator
                                   .decorateClient(collectorRegistry,
                                                   log -> defaultMetricName(log,
                                                                            "HelloService",
                                                                            clientMetricName())))
                .build(Iface.class);
        try {
            client.hello(name);
        } catch (Throwable t) {

        }
    }

    private static AggregatedHttpMessage makeMetricsRequest() throws ExecutionException,
                                                                     InterruptedException {
        HttpClient client = Clients.newClient(ClientFactory.DEFAULT,
                                              "none+http://127.0.0.1:" + server.httpPort(),
                                              HttpClient.class);
        return client.execute(
                HttpHeaders.of(HttpMethod.GET, "/internal/prometheus/metrics-v2")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")).aggregate().get();

    }

    private static MetricLabel defaultMetricName(RequestLog log,
                                                 String serviceName,
                                                 String[] metricNamePrefix) {

        final RequestContext ctx = log.context();
        final Object requestEnvelope = log.requestEnvelope();
        final Object requestContent = log.requestContent();

        String pathAsMetricName = null;
        String methodName = null;

        if (requestEnvelope instanceof HttpHeaders) {
            pathAsMetricName = ctx.path();
            methodName = ((HttpHeaders) requestEnvelope).method().name();
        }

        if (requestContent instanceof RpcRequest) {
            methodName = ((RpcRequest) requestContent).method();
        }

        pathAsMetricName = MoreObjects.firstNonNull(pathAsMetricName, "__UNKNOWN_PATH__");

        if (methodName == null) {
            methodName = MoreObjects.firstNonNull(log.method(), "__UNKNOWN_METHOD__");
        }

        return new MyMetricLabel(metricNamePrefix,
                                 "Armeria client/server request metric",
                                 new MyMetricValue(serviceName, methodName, pathAsMetricName));
    }

    /**
     * Returns the name of the service metric.
     */
    public static String[] serviceMetricName() {
        return new String[] { "armeria", "server" };
    }

    /**
     * Returns the name of the client metric.
     */
    public static String[] clientMetricName() {
        return new String[] { "armeria", "client" };
    }

    public static class MyMetricValue {
        private String handler;
        private String method;
        private String path;

        public MyMetricValue(String handler, String method, String path) {
            this.handler = handler;
            this.method = method;
            this.path = path;
        }

        public String getHandler() {
            return handler;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }
    }

    public static class MyMetricLabel implements MetricLabel<MyMetricValue> {

        private String[] metricNamePrefix;
        private String description;
        private MyMetricValue myMetricValue;

        public MyMetricLabel(String[] metricNamePrefix, String description, MyMetricValue myMetricValue) {
            this.metricNamePrefix = metricNamePrefix;
            this.description = description;
            this.myMetricValue = myMetricValue;
        }

        @Override
        public List<String> getTokenizedName() {
            return ImmutableList.<String>builder().add(metricNamePrefix).build();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public MyMetricValue getValue() {
            return myMetricValue;
        }
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

        //Wait until all RequestLogs are collected.
        latch.await();

        String content = makeMetricsRequest().content().toStringUtf8();
        String helloServiceMethod = "{handler=\"HelloService\",method=\"hello\",path=\"/thrift\",}";

        //Server entry count check
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_timer_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_responseBytes_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_requestBytes_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();

        //Client entry count check
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_timer_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_responseBytes_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_requestBytes_count"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("7.0"))).isTrue();

        //Failure count
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_failure"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("3.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_failure"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("3.0"))).isTrue();

        //Success count
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_success"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("4.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_success"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("4.0"))).isTrue();

        //Active Requests 0
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_server_activeRequests"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("0.0"))).isTrue();
        assertThat(new BufferedReader(new StringReader(content))
                           .lines()
                           .map(String::trim)
                           .filter(line -> line.startsWith("armeria_client_activeRequests"))
                           .filter(line -> line.contains(helloServiceMethod))
                           .anyMatch(line -> line.endsWith("0.0"))).isTrue();
    }
}
