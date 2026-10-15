/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

class MetricCollectingServiceMaxCardinalityTest {

    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().path("/route1").defaultServiceName("route1")
              .build((ctx, req) -> HttpResponse.of(200))
              .route().path("/route2").defaultServiceName("route2")
              .build((ctx, req) -> HttpResponse.of(200))
              .route().path("/route3").defaultServiceName("route3")
              .build((ctx, req) -> HttpResponse.of(200));

            sb.meterRegistry(registry)
              .decorator(MetricCollectingService.builder(MeterIdPrefixFunction.ofDefault("foo"))
                                                .maxCardinality(2)
                                                .newDecorator());
        }
    };

    @Test
    void maxCardinalityExceeded() throws InterruptedException {
        server.webClient().blocking().get("/route1");
        server.webClient().blocking().get("/route2");
        server.webClient().blocking().get("/route1");
        server.webClient().blocking().get("/route3");
        server.webClient().blocking().get("/route2");

        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=200,method=GET," +
                                   "result=success,service=route1}", 2.0)
                    .containsEntry("foo.requests#count{hostname.pattern=*,http.status=200,method=GET," +
                                   "result=success,service=route2}", 2.0);

            // route3 was the 3rd distinct tag combination and exceeds maxCardinality(2), so it must not exist
            assertThat(measurements.keySet().stream().anyMatch(k -> k.contains("service=route3"))).isFalse();
        });
    }
}
