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

package com.linecorp.armeria.client.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class MetricCollectingClientMaxCardinalityTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/1", (ctx, req) -> HttpResponse.of(200));
            sb.service("/2", (ctx, req) -> HttpResponse.of(200));
            sb.service("/3", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void maxCardinalityExceeded() {
        final PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();
        final ClientFactory factory = ClientFactory.builder()
                                                   .meterRegistry(registry)
                                                   .build();
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .factory(factory)
                         .decorator(MetricCollectingClient.builder(
                                 MeterIdPrefixFunction.of(
                                         (reg, log) -> new MeterIdPrefix("client", "path",
                                                                         log.requestHeaders().path())))
                                 .maxCardinality(2)
                                 .newDecorator())
                         .build();

        client.blocking().get("/1");
        client.blocking().get("/2");
        client.blocking().get("/1");
        client.blocking().get("/3");
        client.blocking().get("/2");

        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry("client.requests#count{path=/1,result=success}", 2.0)
                    .containsEntry("client.requests#count{path=/2,result=success}", 2.0);

            // path=/3 was the 3rd distinct tag combination and exceeds maxCardinality(2)
            assertThat(measurements.keySet().stream().anyMatch(k -> k.contains("path=/3"))).isFalse();
        });
    }
}
