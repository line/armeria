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

package com.linecorp.armeria.micrometer.tracing.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Tracer.SpanInScope;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;

/**
 * As illustrated in this test, even if we use micrometer-tracing we still need some
 * custom functionalities for context propagation.
 * We may need to still add a module for opentelemetry integration.
 */
class RequestContextBasedPropagationTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @Test
    void testContextPropagation() {
        final OtelCurrentTraceContext context = new OtelCurrentTraceContext();
        Tracer tracer = new OtelTracer(otelTesting.getOpenTelemetry().getTracer("test"),
                                       context, event -> {});

        final RequestContext rctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        final AtomicReference<TraceContext> contextRef = new AtomicReference<>();
        final AtomicReference<RequestContext> rctxRef = new AtomicReference<>();

        try (SafeCloseable unused = rctx.push()) {
            final Span span = tracer.nextSpan();
            try (SpanInScope scope = tracer.withSpan(span)) {
                assertThat(span.context()).isEqualTo(context.context());

                rctx.eventLoop().execute(() -> {
                    rctxRef.set(RequestContext.currentOrNull());
                    contextRef.set(context.context());
                });
            }
            assertThat(rctxRef).hasValue(rctx);

            // ideally, if context is based on RequestContext this would pass
            assertThat(contextRef).hasValue(span.context());
        }
    }
}
