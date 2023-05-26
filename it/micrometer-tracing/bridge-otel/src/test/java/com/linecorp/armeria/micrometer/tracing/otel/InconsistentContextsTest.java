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

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;

@Disabled
class InconsistentContextsTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    private final Tracer tracer = otelTesting.getOpenTelemetry().getTracer("test");
    private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException();

    private static class TestCurrentTraceContext extends OtelCurrentTraceContext {

        @Override
        public TraceContext context() {
            throw RUNTIME_EXCEPTION;
        }

        @Override
        public Scope newScope(TraceContext context) {
            throw RUNTIME_EXCEPTION;
        }

        @Override
        public Scope maybeScope(TraceContext context) {
            throw RUNTIME_EXCEPTION;
        }
    }

    @Test
    void inconsistentContexts() {
        final OtelCurrentTraceContext tracingContext = new TestCurrentTraceContext();
        final OtelTracer otelTracer = new OtelTracer(tracer, tracingContext, event -> {});
        final RequestContext rctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        try (SafeCloseable safeCloseable = rctx.push()) {
            // succeeds when using the context directly
            assertThatThrownBy(() -> otelTracer.currentTraceContext().context()).isSameAs(RUNTIME_EXCEPTION);

            // fails since TestCurrentTraceContext is never used
            assertThatThrownBy(otelTracer::nextSpan).isSameAs(RUNTIME_EXCEPTION);
        }
    }
}
