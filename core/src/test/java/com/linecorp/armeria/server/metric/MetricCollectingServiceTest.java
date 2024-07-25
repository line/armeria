/*
 * Copyright 2022 LINE Corporation
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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class MetricCollectingServiceTest {

    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // ServerBuilder level
            sb.successFunction((ctx, req) -> req.responseHeaders().status().code() == 401)
              .route().path("/success401").defaultServiceName("success401")
              .build((ctx, req) -> HttpResponse.of(401))
              .route().path("/failure402").defaultServiceName("failure402")
              .build((ctx, req) -> HttpResponse.of(402))
              // Service level successFunction customization
              .route().path("/success402").defaultServiceName("success402")
              .successFunction((ctx, req) -> req.responseHeaders().status().code() == 402)
              .build((ctx, req) -> HttpResponse.of(402))
              // Service level successFunction customization
              .route().path("/failure401").defaultServiceName("failure401")
              .successFunction((ctx, req) -> req.responseHeaders().status().code() == 402)
              .build((ctx, req) -> HttpResponse.of(401));

            sb.meterRegistry(registry)
              .decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("foo")));
        }
    };

    @Test
    void countResponseAsSuccessByServerBuilderSuccessFunction() throws InterruptedException {
        server.webClient().blocking().get("/success401");

        // prometheus requests are collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=401,method=GET," +
                                   "result=success,service=success401}", 1.0);
        });
    }

    @Test
    void countResponseAsFailureByServerBuilderSuccessFunction() throws InterruptedException {
        server.webClient().blocking().get("/failure402");

        // prometheus requests are collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=402,method=GET," +
                                   "result=failure,service=failure402}", 1.0);
        });
    }

    @Test
    void countResponseAsSuccessByServiceBuilderSuccessFunction() throws InterruptedException {
        server.webClient().blocking().get("/success402");

        // prometheus requests are collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=402,method=GET," +
                                   "result=success,service=success402}", 1.0);
        });
    }

    @Test
    void countResponseAsFailureByServiceSuccessFunction() throws InterruptedException {
        server.webClient().blocking().get("/failure401");

        // prometheus requests are collected.
        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=401,method=GET," +
                                   "result=failure,service=failure401}", 1.0);
        });
    }
}
