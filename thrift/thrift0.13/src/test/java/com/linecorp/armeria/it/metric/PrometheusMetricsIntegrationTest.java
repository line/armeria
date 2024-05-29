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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.metric.MetricCollectingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import testing.thrift.main.HelloService.Iface;

public class PrometheusMetricsIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusMetricsIntegrationTest.class);
    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();

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
                    MetricCollectingService.newDecorator(new MeterIdPrefixFunctionImpl("server", "Foo"))));

            sb.service("/bar", helloService.decorate(
                    MetricCollectingService.newDecorator(new MeterIdPrefixFunctionImpl("server", "Bar"))));

            sb.service("/internal/prometheus/metrics",
                       PrometheusExpositionService.of(registry.getPrometheusRegistry()));
        }
    };

    private static final ClientFactory clientFactory =
            ClientFactory.builder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.closeAsync();
    }

    @Rule
    public final TestRule globalTimeout = new DisableOnDebug(new Timeout(30, TimeUnit.SECONDS));

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
        await().untilAsserted(() -> assertThat(makeMetricsRequest().contentUtf8())
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

        final String content = makeMetricsRequest().contentUtf8();
        logger.debug("Metrics reported by the exposition service:\n{}", content);

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("server_request_duration_seconds_count",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() + "\"} 7"));
        assertThat(content).containsPattern(
                multilinePattern("server_request_length_count",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() + "\"} 7"));
        assertThat(content).containsPattern(
                multilinePattern("server_response_length_count",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() + "\"} 7"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("client_request_duration_seconds_count",
                                 "{handler=\"Foo\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 7"));
        assertThat(content).containsPattern(
                multilinePattern("client_request_length_count",
                                 "{handler=\"Foo\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 7"));
        assertThat(content).containsPattern(
                multilinePattern("client_response_length_count",
                                 "{handler=\"Foo\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 7"));

        // Failure count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",result=\"failure\",",
                                 "service=\"" + Iface.class.getName() + "\"} 3.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Foo\",http_status=\"200\",method=\"hello\"," +
                                 "result=\"failure\",service=\"" + Iface.class.getName() + "\"} 3.0"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",result=\"success\",",
                                 "service=\"" + Iface.class.getName() + "\"} 4.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Foo\",http_status=\"200\",method=\"hello\"," +
                                 "result=\"success\",service=\"" + Iface.class.getName() + "\"} 4.0"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("server_active_requests",
                                 "{handler=\"Foo\",hostname_pattern=\"*\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() + "\"} 0.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_active_requests",
                                 "{handler=\"Foo\",method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 0.0"));
    }

    private static void hello_second_endpoint() throws Exception {
        makeRequest2("world");

        // Wait until all RequestLogs are collected.
        await().untilAsserted(() -> assertThat(makeMetricsRequest().contentUtf8())
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

        final String content = makeMetricsRequest().contentUtf8();

        // Server entry count check
        assertThat(content).containsPattern(
                multilinePattern("server_request_duration_seconds_count",
                                 "{handler=\"Bar\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 1"));
        assertThat(content).containsPattern(
                multilinePattern("server_request_length_count",
                                 "{handler=\"Bar\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 1"));
        assertThat(content).containsPattern(
                multilinePattern("server_response_length_count",
                                 "{handler=\"Bar\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 1"));
        // Client entry count check
        assertThat(content).containsPattern(
                multilinePattern("client_request_duration_seconds_count",
                                 "{handler=\"Bar\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 1"));
        assertThat(content).containsPattern(
                multilinePattern("client_request_length_count",
                                 "{handler=\"Bar\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 1"));
        assertThat(content).containsPattern(
                multilinePattern("client_response_length_count",
                                 "{handler=\"Bar\",http_status=\"200\",method=\"hello\",service=\"" +
                                 Iface.class.getName() + "\"} 1"));

        // Success count
        assertThat(content).containsPattern(
                multilinePattern("server_requests_total",
                                 "{handler=\"Bar\",hostname_pattern=\"*\",http_status=\"200\",",
                                 "method=\"hello\",result=\"success\",",
                                 "service=\"" + Iface.class.getName() + "\"} 1.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_requests_total",
                                 "{handler=\"Bar\",http_status=\"200\",method=\"hello\"," +
                                 "result=\"success\",service=\"" + Iface.class.getName() + "\"} 1.0"));

        // Active Requests 0
        assertThat(content).containsPattern(
                multilinePattern("server_active_requests",
                                 "{handler=\"Bar\",hostname_pattern=\"*\",",
                                 "method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 0.0"));
        assertThat(content).containsPattern(
                multilinePattern("client_active_requests",
                                 "{handler=\"Bar\",method=\"hello\",service=\"" + Iface.class.getName() +
                                 "\"} 0.0"));
    }

    private static void makeRequest1(String name) throws TException {
        makeRequest("/foo", "Foo", name);
    }

    private static void makeRequest2(String name) throws TException {
        makeRequest("/bar", "Bar", name);
    }

    private static void makeRequest(String path, String serviceName, String name) throws TException {
        final Iface client = ThriftClients.builder(server.httpUri())
                                          .path(path)
                                          .factory(clientFactory)
                                          .rpcDecorator(MetricCollectingRpcClient.newDecorator(
                                                  new MeterIdPrefixFunctionImpl("client", serviceName)))
                                          .build(Iface.class);
        client.hello(name);
    }

    private static AggregatedHttpResponse makeMetricsRequest() throws ExecutionException,
                                                                      InterruptedException {
        final WebClient client = WebClient.of("http://127.0.0.1:" + server.httpPort());
        return client.execute(RequestHeaders.of(HttpMethod.GET, "/internal/prometheus/metrics",
                                                HttpHeaderNames.ACCEPT, MediaType.PLAIN_TEXT_UTF_8))
                     .aggregate().get();
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

    private static final class MeterIdPrefixFunctionImpl implements MeterIdPrefixFunction {

        private final String name;
        private final String serviceName;

        MeterIdPrefixFunctionImpl(String name, String serviceName) {
            this.name = name;
            this.serviceName = serviceName;
        }

        @Override
        public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
            return MeterIdPrefixFunction.ofDefault(name)
                                        .withTags("handler", serviceName)
                                        .completeRequestPrefix(registry, log);
        }

        @Override
        public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
            return MeterIdPrefixFunction.ofDefault(name)
                                        .withTags("handler", serviceName)
                                        .activeRequestPrefix(registry, log);
        }
    }
}
