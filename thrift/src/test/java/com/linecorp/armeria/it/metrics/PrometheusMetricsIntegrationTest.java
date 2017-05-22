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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.given;

import java.util.EnumSet;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.thrift.TException;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableSortedMap;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.metric.PrometheusMetricCollectionClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MetricLabel;
import com.linecorp.armeria.common.metric.PrometheusRegistry;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.metric.PrometheusExporterHttpService;
import com.linecorp.armeria.server.metric.PrometheusMetricCollectionService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

import io.prometheus.client.CollectorRegistry;

public class PrometheusMetricsIntegrationTest {
    private static final PrometheusRegistry PROMETHEUS_REGISTRY = new PrometheusRegistry();
    private static final CollectorRegistry COLLECTOR_REGISTRY = CollectorRegistry.defaultRegistry;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceAt("/thrift1", THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            }).decorate(
                    PrometheusMetricCollectionService
                            .newDecorator(PROMETHEUS_REGISTRY,
                                          MyMetricLabel.values(),
                                          log -> defaultMetricName(log, "HelloService1"))))
              .serviceAt("/thrift2", THttpService.of((Iface) name -> {
                  if ("world".equals(name)) {
                      return "success";
                  }
                  throw new IllegalArgumentException("bad argument");
              }).decorate(
                      PrometheusMetricCollectionService
                              .newDecorator(PROMETHEUS_REGISTRY,
                                            MyMetricLabel.values(),
                                            log -> defaultMetricName(log, "HelloService2"))))
              .serviceAt("/thrift3", THttpService.of((Iface) name -> {
                  if ("world".equals(name)) {
                      return "success";
                  }
                  throw new IllegalArgumentException("bad argument");
              }).decorate(
                      PrometheusMetricCollectionService
                              .newDecorator(COLLECTOR_REGISTRY,
                                            EnumSet.allOf(MyMetricLabel.class),
                                            log -> defaultMetricName(log, "HelloService3"))))
              .serviceAt("/internal/prometheus/metrics1",
                         new PrometheusExporterHttpService(PROMETHEUS_REGISTRY))
              .serviceAt("/internal/prometheus/metrics2",
                       new PrometheusExporterHttpService(COLLECTOR_REGISTRY));
        }
    };

    private static void makeRequest1(String name) throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, "/thrift1"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           PrometheusMetricCollectionClient
                                   .newDecorator(PROMETHEUS_REGISTRY,
                                                 MyMetricLabel.values(),
                                                 log -> defaultMetricName(log, "HelloService1")))
                .build(Iface.class);
        client.hello(name);
    }

    private static void makeRequest2(String name) throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, "/thrift2"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           PrometheusMetricCollectionClient
                                   .newDecorator(PROMETHEUS_REGISTRY,
                                                 MyMetricLabel.values(),
                                                 log -> defaultMetricName(log, "HelloService2")))
                .build(Iface.class);
        client.hello(name);
    }

    private static void makeRequest3(String name) throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, "/thrift3"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           PrometheusMetricCollectionClient
                                   .newDecorator(COLLECTOR_REGISTRY,
                                                 EnumSet.allOf(MyMetricLabel.class),
                                                 log -> defaultMetricName(log, "HelloService3")))
                .build(Iface.class);
        client.hello(name);
    }

    private static AggregatedHttpMessage makeMetricsRequest1() throws ExecutionException,
                                                                      InterruptedException {
        final HttpClient client = Clients.newClient(ClientFactory.DEFAULT,
                                                    "none+http://127.0.0.1:" + server.httpPort(),
                                                    HttpClient.class);
        return client.execute(HttpHeaders.of(HttpMethod.GET, "/internal/prometheus/metrics1")
                                         .set(HttpHeaderNames.ACCEPT, "utf-8"))
                     .aggregate().get();
    }

    private static AggregatedHttpMessage makeMetricsRequest2() throws ExecutionException,
                                                                      InterruptedException {
        final HttpClient client = Clients.newClient(ClientFactory.DEFAULT,
                                                    "none+http://127.0.0.1:" + server.httpPort(),
                                                    HttpClient.class);
        return client.execute(HttpHeaders.of(HttpMethod.POST, "/internal/prometheus/metrics2")
                                         .set(HttpHeaderNames.ACCEPT, "utf-8"))
                     .aggregate().get();
    }

    private static SortedMap<MyMetricLabel, String> defaultMetricName(RequestLog log,
                                                                      String serviceName) {
        final RequestContext ctx = log.context();
        final Object requestEnvelope = log.requestEnvelope();
        final Object requestContent = log.requestContent();

        final String path;
        final String methodName;

        if (requestContent instanceof RpcRequest) {
            methodName = ((RpcRequest) requestContent).method();
        } else if (requestEnvelope instanceof HttpHeaders) {
            methodName = ((HttpHeaders) requestEnvelope).method().name();
        } else {
            methodName = log.method();
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

    private enum MyMetricLabel implements MetricLabel<MyMetricLabel> {
        path,
        handler,
        method,
    }

    private void hello_first_endpoint() throws Exception {

        makeRequest1("world");
        makeRequest1("world");
        assertThatThrownBy(() -> makeRequest1("space"));
        makeRequest1("world");
        assertThatThrownBy(() -> makeRequest1("space"));
        assertThatThrownBy(() -> makeRequest1("space"));
        makeRequest1("world");

        //Wait until all RequestLogs are collected.
        given().atMost(10000L, TimeUnit.MILLISECONDS)
               .ignoreExceptions()
               .untilAsserted(() -> assertThat(makeMetricsRequest1().content().toStringUtf8())
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

        final String content = makeMetricsRequest1().content().toStringUtf8();

        //Server entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_duration_seconds_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_size_bytes_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_response_size_bytes_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));
        //Client entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_duration_seconds_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_size_bytes_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_response_size_bytes_count\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 7.0$",
                                Pattern.MULTILINE));

        //Failure count
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_failure_total\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 3.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_failure_total\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 3.0$",
                                Pattern.MULTILINE));

        //Success count
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_success_total\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 4.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_success_total\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 4.0$",
                                Pattern.MULTILINE));

        //Active Requests 0
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_active\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_active\\{path=\\\"/thrift1\\\"," +
                                "handler=\\\"HelloService1\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
    }

    private void hello_second_endpoint_same_registry() throws Exception {
        makeRequest2("world");

        //Wait until all RequestLogs are collected.
        given().atMost(10000L, TimeUnit.MILLISECONDS)
               .ignoreExceptions()
               .untilAsserted(() -> assertThat(makeMetricsRequest1().content().toStringUtf8())
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

        final String content = makeMetricsRequest1().content().toStringUtf8();

        //Server entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_duration_seconds_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_size_bytes_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_response_size_bytes_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        //Client entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_duration_seconds_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_size_bytes_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_response_size_bytes_count\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));

        //Success count
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_success_total\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_success_total\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));

        //Active Requests 0
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_active\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_active\\{path=\\\"/thrift2\\\"," +
                                "handler=\\\"HelloService2\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
    }

    private void hello_third_endpoint_normal_registry() throws Exception {
        makeRequest3("world");

        //Wait until all RequestLogs are collected.
        given().atMost(10000L, TimeUnit.MILLISECONDS)
               .ignoreExceptions()
               .untilAsserted(() -> assertThat(makeMetricsRequest2().content().toStringUtf8())
                       .contains("armeria_server_request_duration_seconds_count{path=\"/thrift3",
                                 "armeria_server_request_size_bytes_count{path=\"/thrift3",
                                 "armeria_server_response_size_bytes_count{path=\"/thrift3",
                                 "armeria_client_request_duration_seconds_count{path=\"/thrift3",
                                 "armeria_client_request_size_bytes_count{path=\"/thrift3",
                                 "armeria_client_response_size_bytes_count{path=\"/thrift3",
                                 "armeria_server_request_success_total{path=\"/thrift3",
                                 "armeria_client_request_success_total{path=\"/thrift3",
                                 "armeria_server_request_active{path=\"/thrift3",
                                 "armeria_client_request_active{path=\"/thrift3"));

        final String content = makeMetricsRequest2().content().toStringUtf8();

        //Server entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_duration_seconds_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_size_bytes_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_response_size_bytes_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        //Client entry count check
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_duration_seconds_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_size_bytes_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_response_size_bytes_count\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));

        //Success count
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_success_total\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_success_total\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 1.0$",
                                Pattern.MULTILINE));

        //Active Requests 0
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_server_request_active\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
        assertThat(content).containsPattern(
                Pattern.compile("^armeria_client_request_active\\{path=\\\"/thrift3\\\"," +
                                "handler=\\\"HelloService3\\\",method=\\\"hello\\\",\\} 0.0$",
                                Pattern.MULTILINE));
    }

    @Test
    public void hello_first_second_thrid_endpoint() throws Exception {
        hello_first_endpoint();
        hello_second_endpoint_same_registry();
        hello_third_endpoint_normal_registry();
    }
}
