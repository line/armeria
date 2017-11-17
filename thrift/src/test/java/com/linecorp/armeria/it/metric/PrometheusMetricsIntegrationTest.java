/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;

public class PrometheusMetricsIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusMetricsIntegrationTest.class);
    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
    private static final CollectorRegistry prometheusRegistry = registry.getPrometheusRegistry();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.meterRegistry(registry);

            final THttpService helloService = THttpService.of((Iface) name -> {
                if ("world".equals(name)) {
                    return "success";
                }
                throw new IllegalArgumentException("bad argument");
            });

            sb.service("/foo", helloService.decorate(
                    MetricCollectingService.newDecorator(
                            (registry, log) -> meterIdPrefix(registry, log, "server", "Foo"))));

            sb.service("/bar", helloService.decorate(
                    MetricCollectingService.newDecorator(
                            (registry, log) -> meterIdPrefix(registry, log, "server", "Bar"))));

            sb.service("/internal/prometheus/metrics",
                       new PrometheusExpositionService(prometheusRegistry));
        }
    };

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.close();
    }

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
                .contains("server_active_requests{handler=\"Foo\"",
                          "server_active_requests{handler=\"Foo\"",
                          "server_requests_total{handler=\"Foo\",",
                          "server_request_duration_seconds_count{handler=\"Foo\",",
                          "server_request_duration_seconds_sum{handler=\"Foo\",",
                          "server_request_length_count{handler=\"Foo\",",
                          "server_request_length_sum{handler=\"Foo\",",
                          "server_response_duration_seconds_count{handler=\"Foo\",",
                          "server_response_duration_seconds_sum{handler=\"Foo\",",
                          "server_response_length_count{handler=\"Foo\",",
                          "server_response_length_sum{handler=\"Foo\",",
                          "server_total_duration_seconds_count{handler=\"Foo\",",
                          "server_total_duration_seconds_sum{handler=\"Foo\",",
                          "client_active_requests{handler=\"Foo\"",
                          "client_requests_total{handler=\"Foo\",",
                          "client_request_duration_seconds_count{handler=\"Foo\",",
                          "client_request_duration_seconds_sum{handler=\"Foo\",",
                          "client_request_length_count{handler=\"Foo\",",
                          "client_request_length_sum{handler=\"Foo\",",
                          "client_response_duration_seconds_count{handler=\"Foo\",",
                          "client_response_duration_seconds_sum{handler=\"Foo\",",
                          "client_response_length_count{handler=\"Foo\",",
                          "client_response_length_sum{handler=\"Foo\",",
                          "client_total_duration_seconds_count{handler=\"Foo\",",
                          "client_total_duration_seconds_sum{handler=\"Foo\","));

        final String content = makeMetricsRequest().content().toStringUtf8();
        logger.debug("Metrics reported by the exposition service:\n{}", content);

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("server_request_duration_seconds_count",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",} 7.0"));
        assertThat(content).containsPattern(
                multilinePattern("server_request_length_count",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",} 7.0"));
        assertThat(content).containsPattern(
                multilinePattern("server_response_length_count",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",} 7.0"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("client_request_duration_seconds_count",
                                 "{handler=\"Foo\",method=\"hello\",} 7.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_request_length_count",
                                 "{handler=\"Foo\",method=\"hello\",} 7.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_response_length_count",
                                 "{handler=\"Foo\",method=\"hello\",} 7.0"));

        // Failure count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",",
                                 "result=\"failure\",} 3.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Foo\",method=\"hello\",result=\"failure\",} 3.0"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",",
                                 "result=\"success\",} 4.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Foo\",method=\"hello\",result=\"success\",} 4.0"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("server_active_requests",
                                 "{handler=\"Foo\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/foo\",} 0.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_active_requests",
                                 "{handler=\"Foo\",method=\"hello\",} 0.0"));
    }

    private static void hello_second_endpoint() throws Exception {
        makeRequest2("world");

        // Wait until all RequestLogs are collected.
        await().untilAsserted(() -> assertThat(makeMetricsRequest().content().toStringUtf8())
                .contains("server_active_requests{handler=\"Bar\"",
                          "server_active_requests{handler=\"Bar\"",
                          "server_requests_total{handler=\"Bar\",",
                          "server_request_duration_seconds_count{handler=\"Bar\",",
                          "server_request_duration_seconds_sum{handler=\"Bar\",",
                          "server_request_length_count{handler=\"Bar\",",
                          "server_request_length_sum{handler=\"Bar\",",
                          "server_response_duration_seconds_count{handler=\"Bar\",",
                          "server_response_duration_seconds_sum{handler=\"Bar\",",
                          "server_response_length_count{handler=\"Bar\",",
                          "server_response_length_sum{handler=\"Bar\",",
                          "server_total_duration_seconds_count{handler=\"Bar\",",
                          "server_total_duration_seconds_sum{handler=\"Bar\",",
                          "client_active_requests{handler=\"Bar\"",
                          "client_requests_total{handler=\"Bar\",",
                          "client_request_duration_seconds_count{handler=\"Bar\",",
                          "client_request_duration_seconds_sum{handler=\"Bar\",",
                          "client_request_length_count{handler=\"Bar\",",
                          "client_request_length_sum{handler=\"Bar\",",
                          "client_response_duration_seconds_count{handler=\"Bar\",",
                          "client_response_duration_seconds_sum{handler=\"Bar\",",
                          "client_response_length_count{handler=\"Bar\",",
                          "client_response_length_sum{handler=\"Bar\",",
                          "client_total_duration_seconds_count{handler=\"Bar\",",
                          "client_total_duration_seconds_sum{handler=\"Bar\","));

        final String content = makeMetricsRequest().content().toStringUtf8();

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("server_request_duration_seconds_count",
                                 "{handler=\"Bar\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/bar\",} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("server_request_length_count",
                                 "{handler=\"Bar\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/bar\",} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("server_response_length_count",
                                 "{handler=\"Bar\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/bar\",} 1.0"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("client_request_duration_seconds_count",
                                 "{handler=\"Bar\",method=\"hello\",} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_request_length_count",
                                 "{handler=\"Bar\",method=\"hello\",} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_response_length_count",
                                 "{handler=\"Bar\",method=\"hello\",} 1.0"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Bar\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/bar\",",
                                 "result=\"success\",} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Bar\",method=\"hello\",result=\"success\",} 1.0"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("server_active_requests",
                                 "{handler=\"Bar\",hostnamePattern=\"*\",",
                                 "method=\"hello\",pathMapping=\"exact:/bar\",} 0.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_active_requests",
                                 "{handler=\"Bar\",method=\"hello\",} 0.0"));
    }

    private static void makeRequest1(String name) throws TException {
        makeRequest("/foo", "Foo", name);
    }

    private static void makeRequest2(String name) throws TException {
        makeRequest("/bar", "Bar", name);
    }

    private static void makeRequest(String path, String serviceName, String name) throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, path))
                .factory(clientFactory)
                .decorator(RpcRequest.class, RpcResponse.class,
                           MetricCollectingClient.newDecorator(
                                   (registry, log) -> meterIdPrefix(registry, log, "client", serviceName)))
                .build(Iface.class);
        client.hello(name);
    }

    private static AggregatedHttpMessage makeMetricsRequest() throws ExecutionException,
                                                                     InterruptedException {
        final HttpClient client = HttpClient.of("http://127.0.0.1:" + server.httpPort());
        return client.execute(HttpHeaders.of(HttpMethod.GET, "/internal/prometheus/metrics")
                                         .setObject(HttpHeaderNames.ACCEPT, MediaType.PLAIN_TEXT_UTF_8))
                     .aggregate().get();
    }

    private static MeterIdPrefix meterIdPrefix(MeterRegistry registry, RequestLog log,
                                               String name, String serviceName) {
        return MeterIdPrefixFunction.ofDefault(name)
                                    .withTags("handler", serviceName)
                                    .apply(registry, log);
    }

    private static Pattern multilinePattern(String... lines) {
        final StringBuilder buf = new StringBuilder();

        buf.append('^');
        for (String l : lines) {
            buf.append(Pattern.quote(l));
        }
        buf.append('$');

        return Pattern.compile(buf.toString(), Pattern.MULTILINE);
    }
}
