/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.metric.MicrometerUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DuplicateMetricCollectingServiceTest {

    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(meterRegistry);

            sb.route()
              .get("/foo/")
              .defaultServiceName("FooService")
              .build((ctx, req) -> HttpResponse.of("FooService"));

            sb.route()
              .get("/foo/bar")
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("bar.metrics")))
              .decorator(LoggingService.newDecorator())
              .defaultServiceName("BarService")
              .build((ctx, req) -> HttpResponse.of("BarService"));

            sb.route()
              .get("/baz")
              .defaultServiceName("BazService")
              .build((ctx, req) -> HttpResponse.of("BazService"));

            sb.decoratorUnder("/foo",
                              MetricCollectingService.newDecorator(
                                      MeterIdPrefixFunction.ofDefault("foo.metrics")));
            sb.decoratorUnder("/foo", LoggingService.newDecorator());
            sb.decorator(
                    MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("global.metrics")));
        }
    };

    @RegisterExtension
    static ServerExtension serverNoRoutingDecorators = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(meterRegistry);

            sb.route()
              .get("/foo")
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("foo.metrics")))
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("bar.metrics")))
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("baz.metrics")))
              .defaultServiceName("FooService")
              .build((ctx, req) -> HttpResponse.of("FooService"));
        }
    };

    @AfterEach
    void tearDown() {
        clearMeters();
    }

    @Test
    void shouldRespectInnermostDecorator() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        // Execute multiple times to check the routing result cached in MetricCollectingService.
        for (int i = 0; i < 3; i++) {
            AggregatedHttpResponse response = client.get("/foo/bar");
            assertThat(response.contentUtf8()).isEqualTo("BarService");
            assertMeters("BarService", "bar.metrics", ImmutableList.of("global.metrics", "foo.metrics"));
            clearMeters();

            response = client.get("/foo/");
            assertThat(response.contentUtf8()).isEqualTo("FooService");
            assertMeters("FooService", "foo.metrics", ImmutableList.of("global.metrics", "bar.metrics"));
            clearMeters();

            response = client.get("/baz");
            assertThat(response.contentUtf8()).isEqualTo("BazService");
            assertMeters("BazService", "global.metrics", ImmutableList.of("foo.metrics", "bar.metrics"));
            clearMeters();
        }
    }

    @Test
    void shouldRespectInnermostDecorator_noRoutingDecorators() throws InterruptedException {
        final BlockingWebClient client = serverNoRoutingDecorators.blockingWebClient();
        // Execute multiple times to check the routing result cached in MetricCollectingService.
        for (int i = 0; i < 3; i++) {
            final AggregatedHttpResponse response = client.get("/foo");
            assertThat(response.contentUtf8()).isEqualTo("FooService");
            assertMeters("FooService", "foo.metrics", ImmutableList.of("bar.metrics", "baz.metrics"));
            clearMeters();
        }
    }

    private static void assertMeters(String serviceName, String meterName, List<String> unexpectedMeterNames) {
        await().untilAsserted(() -> {
            final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
            assertThat(metrics).allSatisfy((meterId, value) -> {
                for (String unexpectedMeterName : unexpectedMeterNames) {
                    assertThat(meterId).doesNotStartWith(unexpectedMeterName);
                }
            });
            assertThat(metrics).containsEntry(
                    meterName + ".requests#count{hostname.pattern=*,http.status=200,method=GET," +
                    "result=success,service=" + serviceName + '}', 1.0);
        });
    }

    private static void clearMeters() {
        meterRegistry.clear();
        MicrometerUtil.clear();
    }
}
