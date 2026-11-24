/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.server.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;

class BraveServiceTransientRouteDecoratorTest {

    private static final Set<String> tracedPaths = ConcurrentHashMap.newKeySet();

    private static final Tracing tracing =
            Tracing.newBuilder()
                   .localServiceName("test-service")
                   .addSpanHandler(new SpanHandler() {
                       @Override
                       public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                           final String path = span.tag("http.path");
                           if (path != null) {
                               tracedPaths.add(path);
                           }
                           return true;
                       }
                   })
                   .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                   .sampler(Sampler.ALWAYS_SAMPLE)
                   .build();

    private static final HttpService fooService = (ctx, req) -> HttpResponse.of("foo");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(BraveService.newDecorator(tracing));

            sb.service("/non-transient", fooService);

            sb.service("/route-transient", fooService);
            sb.decorator("/route-transient", TransientHttpService.newDecorator());

            sb.service("/route-transient-with-tracing", fooService);
            sb.decorator("/route-transient-with-tracing",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_TRACING));
        }
    };

    @AfterAll
    static void closeTracing() {
        tracing.close();
    }

    @BeforeEach
    void setUp() {
        tracedPaths.clear();
    }

    @CsvSource({
            "/non-transient,                true",
            "/route-transient,              false",
            "/route-transient-with-tracing, true",
    })
    @ParameterizedTest
    void routeDecorator(String path, boolean traced) throws Exception {
        assertThat(server.blockingWebClient().get(path).status().code()).isEqualTo(200);
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        ctx.log().whenComplete().join();

        if (traced) {
            await().untilAsserted(() -> assertThat(tracedPaths).contains(path));
        } else {
            await().during(Duration.ofMillis(300))
                   .atMost(Duration.ofSeconds(3))
                   .untilAsserted(() -> assertThat(tracedPaths).doesNotContain(path));
        }
    }
}
