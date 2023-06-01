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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
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
            sb.decoratorUnder("/foo",
                              LoggingService.newDecorator());
            sb.decorator(
                    MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("global.metrics")));
        }
    };

    @Test
    void shouldRespectInnermostDecorator() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        AggregatedHttpResponse response = client.get("/foo/bar");
        assertThat(response.contentUtf8()).isEqualTo("BarService");

        await().untilAsserted(() -> {
            final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
            assertThat(metrics).allSatisfy((meterId, value) -> {
                assertThat(meterId).doesNotStartWith("global.metrics");
                assertThat(meterId).doesNotStartWith("foo.metrics");
            });
            assertThat(metrics).containsEntry(
                    "bar.metrics.requests#count{hostname.pattern=*,http.status=200,method=GET," +
                    "result=success,service=BarService}", 1.0);
        });
        meterRegistry.clear();

        response = client.get("/foo/");
        assertThat(response.contentUtf8()).isEqualTo("FooService");

        await().untilAsserted(() -> {
            final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
            assertThat(metrics).allSatisfy((meterId, value) -> {
                assertThat(meterId).doesNotStartWith("global.metrics");
                assertThat(meterId).doesNotStartWith("bar.metrics");
            });
            assertThat(metrics).containsEntry(
                    "foo.metrics.requests#count{hostname.pattern=*,http.status=200,method=GET," +
                    "result=success,service=FooService}", 1.0);
        });
        meterRegistry.clear();

        response = client.get("/baz");
        assertThat(response.contentUtf8()).isEqualTo("BazService");

        await().untilAsserted(() -> {
            final Map<String, Double> fooMetrics = MoreMeters.measureAll(meterRegistry);
            assertThat(fooMetrics).allSatisfy((meterId, value) -> {
                assertThat(meterId).doesNotStartWith("foo.metrics");
                assertThat(meterId).doesNotStartWith("bar.metrics");
            });
            assertThat(fooMetrics).containsEntry(
                    "global.metrics.requests#count{hostname.pattern=*,http.status=200,method=GET," +
                    "result=success,service=BazService}", 1.0);
        });
        meterRegistry.clear();
    }
}
