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

package com.linecorp.armeria.micrometer.tracing.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import brave.Tracing;
import brave.sampler.Sampler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Tracer.SpanInScope;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;

class RequestContextBasedPropagationTest {

    @Test
    void testContextPropagation() {
        final SpanCollector collector = new SpanCollector();

        final Tracing tracing = Tracing.newBuilder()
                                       .addSpanHandler(collector)
                                       // fails when commenting out this line
                                       .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                                       .sampler(Sampler.create(1.0f))
                                       .build();

        final BraveCurrentTraceContext context = new BraveCurrentTraceContext(RequestContextCurrentTraceContext.ofDefault());
        final Tracer tracer = new BraveTracer(tracing.tracer(), context);

        final RequestContext rctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        final AtomicReference<TraceContext> contextRef = new AtomicReference<>();
        final AtomicReference<RequestContext> rctxRef = new AtomicReference<>();

        final ForkJoinPool fjp = ForkJoinPool.commonPool();

        try (SafeCloseable unused = rctx.push()) {
            final Span span = tracer.nextSpan();
            rctx.eventLoop().execute(() -> {
                try (SpanInScope scope = tracer.withSpan(span)) {
                    rctx.makeContextAware(fjp).execute(() -> {
                        contextRef.set(context.context());
                        rctxRef.set(RequestContext.currentOrNull());
                    });
                }
            });
            await().untilAsserted(() -> assertThat(rctxRef).hasValue(rctx));

            // ideally, if context is based on RequestContext this would pass
            assertThat(contextRef).hasValue(span.context());
        }
    }
}
