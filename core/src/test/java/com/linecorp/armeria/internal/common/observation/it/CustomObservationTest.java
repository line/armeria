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

package com.linecorp.armeria.internal.common.observation.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.observation.ClientObservationContext;
import com.linecorp.armeria.client.observation.ObservationClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.common.observation.MicrometerObservationRegistryUtils;
import com.linecorp.armeria.internal.common.observation.SpanCollector;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.observation.ObservationService;
import com.linecorp.armeria.server.observation.ServiceObservationContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.Tracing;
import brave.sampler.Sampler;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

class CustomObservationTest {

    private static final SpanCollector spanHandler = new SpanCollector();

    private static ObservationRegistry newTracing(String name) {
        return MicrometerObservationRegistryUtils.observationRegistry(tracingBuilder(name));
    }

    private static Tracing tracingBuilder(String name) {
        return Tracing.newBuilder()
                      .localServiceName(name)
                      .addSpanHandler(spanHandler)
                      .sampler(Sampler.ALWAYS_SAMPLE)
                      .build();
    }

    private static Function<? super HttpService, ObservationService>
    customConventionServiceDecorator(String name) {
        final ObservationConvention<ServiceObservationContext> convention =
                new ObservationConvention<ServiceObservationContext>() {
                    @Override
                    public KeyValues getLowCardinalityKeyValues(ServiceObservationContext context) {
                        return KeyValues.of("ctx.id", context.requestContext().id().shortText());
                    }

                    @Override
                    public String getName() {
                        return "custom.convention.service";
                    }

                    @Override
                    public boolean supportsContext(Context context) {
                        return context instanceof ServiceObservationContext;
                    }
                };
        return ObservationService.newDecorator(newTracing(name), convention);
    }

    private static Function<? super HttpClient, ObservationClient>
    customConventionClientDecorator(String name) {
        final ObservationConvention<ClientObservationContext> convention =
                new ObservationConvention<ClientObservationContext>() {
                    @Override
                    public KeyValues getLowCardinalityKeyValues(ClientObservationContext context) {
                        return KeyValues.of("ctx.id", context.requestContext().id().shortText());
                    }

                    @Override
                    public String getName() {
                        return "custom.convention.client";
                    }

                    @Override
                    public boolean supportsContext(Context context) {
                        return context instanceof ClientObservationContext;
                    }
                };
        return ObservationClient.newDecorator(newTracing(name), convention);
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route()
              .path("/foo")
              .decorator(customConventionServiceDecorator("tracing/foo"))
              .build((ctx, req) -> HttpResponse.of("foo"));
        }
    };

    @BeforeEach
    void beforeEach() {
        spanHandler.spans().clear();
    }

    @Test
    void testCustomConvention() throws Exception {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final BlockingWebClient client = server.blockingWebClient(
                    cb -> cb.decorator(customConventionClientDecorator("tracing/foo")));
            assertThat(client.get("/foo").contentUtf8()).isEqualTo("foo");
            await().until(() -> spanHandler.spans().size() == 2);
            final List<String> entries =
                    spanHandler.spans().stream().flatMap(span -> span.tags().values().stream())
                               .collect(Collectors.toList());
            assertThat(entries).containsExactlyInAnyOrder(
                    captor.get().id().shortText(),
                    server.requestContextCaptor().take().id().shortText());
        }
    }
}
