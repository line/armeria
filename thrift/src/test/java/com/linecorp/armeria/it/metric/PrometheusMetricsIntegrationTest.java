/*
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

package com.linecorp.armeria.it.metric;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.EnumSet;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableSortedMap;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.metric.PrometheusMetricCollectingClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MetricLabel;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.PrometheusExporterHttpService;
import com.linecorp.armeria.server.metric.PrometheusMetricCollectingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

import io.prometheus.client.CollectorRegistry;

public class PrometheusMetricsIntegrationTest {

    private static final CollectorRegistry registry = new CollectorRegistry();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final THttpService helloService = THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            });

            sb.service("/thrift1", helloService.decorate(
                    PrometheusMetricCollectingService
                            .newDecorator(registry, MyMetricLabel.values(),
                                          log -> defaultMetricName(log, "HelloService1"))));

            sb.service("/thrift2", helloService.decorate(
                    PrometheusMetricCollectingService
                            .newDecorator(registry, EnumSet.allOf(MyMetricLabel.class),
                                          log -> defaultMetricName(log, "HelloService2"))));

            sb.service("/internal/prometheus/metrics",
                         new PrometheusExporterHttpService(registry));
        }
    };

    @Test
    public void hello_first_second_endpoint() throws Exception {
        hello_first_endpoint();
        hello_second_endpoint();
    }

    private static void hello_first_endpoint() throws Exception {
        makeRequest1("world");
        makeRequest1("world");
        assertThatThrownBy(() -> makeRequest1("space")).isInstanceOf(TApplicationException.class);
        makeRequest1("world");
        assertThatThrownBy(() -> makeRequest1("space")).isInstanceOf(TApplicationException.class);
        assertThatThrownBy(() -> makeRequest1("space")).isInstanceOf(TApplicationException.class);
        makeRequest1("world");

        // Wait until all RequestLogs are collected.
        await().untilAsserted(() -> assertThat(makeMetricsRequest().content().toStringUtf8())
                .contains("armeria_server_request_duration_seconds_count{path=\"/thrift1",
                          "armeria_server_request_size_bytes_count{path=\"/thrift1",
                          "armeria_server_response_size_bytes_count{path=\"/thrift1",
                          "armeria_client_request_duration_seconds_count{path=\"/thrift1",
                          "armeria_client_request_size_bytes_count{path=\"/thrift1",
                          "armeria_client_response_size_bytes_count{path=\"/thrift1",
                          "armeria_server_request_success_total{path=\"/thrift1",
                          "armeria_server_request_failure_total{path=\"/thrift1",
                          "armeria_client_request_success_total{path=\"/thrift1",
                          "armeria_client_request_failure_total{path=\"/thrift1",
                          "armeria_server_request_active{path=\"/thrift1",
                          "armeria_client_request_active{path=\"/thrift1"));

        final String content = makeMetricsRequest().content().toStringUtf8();

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_duration_seconds_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_size_bytes_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_response_size_bytes_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_duration_seconds_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_size_bytes_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_response_size_bytes_count\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 7.0$"));

        // Failure count
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_failure_total\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 3.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_failure_total\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 3.0$"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_success_total\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 4.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_success_total\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 4.0$"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_active\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 0.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_active\\{path=\"/thrift1\"," +
                                 "handler=\"HelloService1\",method=\"hello\",} 0.0$"));
    }

    private static void hello_second_endpoint() throws Exception {
        makeRequest2("world");

        // Wait until all RequestLogs are collected.
        await().untilAsserted(() -> assertThat(makeMetricsRequest().content().toStringUtf8())
                .contains("armeria_server_request_duration_seconds_count{path=\"/thrift2",
                          "armeria_server_request_size_bytes_count{path=\"/thrift2",
                          "armeria_server_response_size_bytes_count{path=\"/thrift2",
                          "armeria_client_request_duration_seconds_count{path=\"/thrift2",
                          "armeria_client_request_size_bytes_count{path=\"/thrift2",
                          "armeria_client_response_size_bytes_count{path=\"/thrift2",
                          "armeria_server_request_success_total{path=\"/thrift2",
                          "armeria_client_request_success_total{path=\"/thrift2",
                          "armeria_server_request_active{path=\"/thrift2",
                          "armeria_client_request_active{path=\"/thrift2"));

        final String content = makeMetricsRequest().content().toStringUtf8();

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_duration_seconds_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_size_bytes_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_response_size_bytes_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_duration_seconds_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_size_bytes_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_response_size_bytes_count\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_success_total\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_success_total\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 1.0$"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("^armeria_server_request_active\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 0.0$"));
        assertThat(content).containsPattern(
                multilinePattern("^armeria_client_request_active\\{path=\"/thrift2\"," +
                                 "handler=\"HelloService2\",method=\"hello\",} 0.0$"));
    }

    private static void makeRequest1(String name) throws TException {
        makeRequest("/thrift1", "HelloService1", name);
    }

    private static void makeRequest2(String name) throws TException {
        makeRequest("/thrift2", "HelloService2", name);
    }

    private static void makeRequest(String path, String serviceName, String name) throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, path))
                .decorator(RpcRequest.class, RpcResponse.class,
                           PrometheusMetricCollectingClient
                                   .newDecorator(registry, EnumSet.allOf(MyMetricLabel.class),
                                                 log -> defaultMetricName(log, serviceName)))
                .build(Iface.class);
        client.hello(name);
    }

    private static AggregatedHttpMessage makeMetricsRequest() throws ExecutionException,
                                                                     InterruptedException {
        final HttpClient client = Clients.newClient("none+http://127.0.0.1:" + server.httpPort(),
                                                    HttpClient.class);
        return client.execute(HttpHeaders.of(HttpMethod.GET, "/internal/prometheus/metrics")
                                         .setObject(HttpHeaderNames.ACCEPT, MediaType.PLAIN_TEXT_UTF_8))
                     .aggregate().get();
    }

    private static SortedMap<MyMetricLabel, String> defaultMetricName(RequestLog log, String serviceName) {
        final RequestContext ctx = log.context();
        final Object requestEnvelope = log.requestHeaders();
        final Object requestContent = log.requestContent();

        final String path;
        final String methodName;

        if (requestContent instanceof RpcRequest) {
            methodName = ((RpcRequest) requestContent).method();
        } else if (requestEnvelope instanceof HttpHeaders) {
            methodName = ((HttpHeaders) requestEnvelope).method().name();
        } else {
            methodName = log.method().name();
        }

        if (requestEnvelope instanceof HttpHeaders) {
            path = ctx.path();
        } else {
            path = null;
        }

        return ImmutableSortedMap.<MyMetricLabel, String>naturalOrder()
                .put(MyMetricLabel.handler, serviceName)
                .put(MyMetricLabel.path, firstNonNull(path, "__UNKNOWN_PATH__"))
                .put(MyMetricLabel.method, firstNonNull(methodName, "__UNKNOWN_METHOD__"))
                .build();
    }

    private static Pattern multilinePattern(String regex) {
        return Pattern.compile(regex, Pattern.MULTILINE);
    }

    private enum MyMetricLabel implements MetricLabel<MyMetricLabel> {
        path,
        handler,
        method,
    }
}
