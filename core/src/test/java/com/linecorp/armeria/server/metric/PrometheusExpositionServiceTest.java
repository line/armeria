/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;

class PrometheusExpositionServiceTest {

    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();

    private static final Queue<RequestLog> logs = new ConcurrentLinkedQueue<>();

    private static final Logger logger = mock(Logger.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().path("/api").defaultServiceName("Hello").build((ctx, req) -> HttpResponse.of(200));
            sb.meterRegistry(registry)
              .decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("foo")))
              .service("/disabled", PrometheusExpositionService.of(registry.getPrometheusRegistry()))
              .service("/enabled",
                       PrometheusExpositionService.builder(registry.getPrometheusRegistry())
                                                  .transientServiceOptions(TransientServiceOption.allOf())
                                                  .build());
            sb.accessLogWriter(logs::add, false);
            sb.decorator(LoggingService.builder()
                                       .logWriter(LogWriter.of(logger))
                                       .newDecorator());
        }
    };

    @Test
    void prometheusRequests() throws InterruptedException {
        when(logger.isDebugEnabled()).thenReturn(true);
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/api").aggregate().join().status()).isSameAs(HttpStatus.OK);
        await().until(() -> logs.size() == 1);
        verify(logger, times(2)).isDebugEnabled();
        verify(logger, times(2)).debug(anyString());

        final String exportedContent = client.get("/disabled").aggregate().join().contentUtf8();
        assertThat(exportedContent).contains("armeria_build_info{");
        // The last line must end with a line feed character.
        // see https://prometheus.io/docs/instrumenting/exposition_formats/
        assertThat(exportedContent).endsWith("\n");

        // prometheus requests are not collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.active.requests#value{hostname.pattern=*,method=GET,service=Hello}",
                                   0.0)
                    .doesNotContainKey("foo.active.requests#value{hostname.pattern=*,method=GET,service=" +
                                       "com.linecorp.armeria.server.metric.PrometheusExpositionService}");
        });
        // Access log is not written.
        await().pollDelay(500, TimeUnit.MILLISECONDS).then().until(() -> logs.size() == 1);
        verify(logger, times(2)).isDebugEnabled();
        verify(logger, times(2)).debug(anyString());

        client.get("/enabled").aggregate().join();
        // prometheus requests are collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.active.requests#value{hostname.pattern=*,method=GET,service=Hello}",
                                   0.0)
                    .containsEntry("foo.active.requests#value{hostname.pattern=*,method=GET,service=" +
                                   "com.linecorp.armeria.server.metric.PrometheusExpositionService}", 0.0);
        });
        // Access log is written.
        await().pollDelay(500, TimeUnit.MILLISECONDS).until(() -> logs.size() == 2);
        verify(logger, times(4)).isDebugEnabled();
        verify(logger, times(4)).debug(anyString());
    }

    @Nested
    class FormatTest {
        @Test
        void prometheusRequestsPrometheusFormat() throws InterruptedException {
            final WebClient client = WebClient.of(server.httpUri());
            final HttpRequest request = HttpRequest.builder()
                                                   .get("/enabled")
                                                   .header(HttpHeaderNames.ACCEPT, TextFormat.CONTENT_TYPE_004)
                                                   .build();
            final AggregatedHttpResponse response = client.execute(request).aggregate().join();
            assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                    .isEqualTo(TextFormat.CONTENT_TYPE_004);
        }

        @Test
        void prometheusRequestsOpenMetricsFormat() throws InterruptedException {
            final WebClient client = WebClient.of(server.httpUri());
            final HttpRequest request = HttpRequest.builder()
                                                   .get("/enabled")
                                                   .header(HttpHeaderNames.ACCEPT,
                                                           TextFormat.CONTENT_TYPE_OPENMETRICS_100)
                                                   .build();
            final AggregatedHttpResponse response = client.execute(request).aggregate().join();
            assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                    .isEqualTo(TextFormat.CONTENT_TYPE_OPENMETRICS_100);
        }
    }
}
