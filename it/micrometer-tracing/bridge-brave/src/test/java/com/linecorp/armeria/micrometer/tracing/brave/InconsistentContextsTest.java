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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.CurrentTraceContext.Scope;
import io.micrometer.tracing.Tracer.SpanInScope;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;

@Disabled
class InconsistentContextsTest {

    private static class TestCurrentTraceContext extends CurrentTraceContext {

        @Override
        public TraceContext get() {
            throw new RuntimeException("shouldn't be used");
        }

        @Override
        public Scope newScope(TraceContext context) {
            throw new RuntimeException("shouldn't be used");
        }
    }

    @Test
    void inconsistentContexts() {
        final SpanCollector collector = new SpanCollector();

        final TestCurrentTraceContext context = new TestCurrentTraceContext();
        final Tracing tracing = Tracing.newBuilder()
                                       .addSpanHandler(collector)
                                       .currentTraceContext(context)
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .build();

        final BraveCurrentTraceContext tracingContext =
                new BraveCurrentTraceContext(ThreadLocalCurrentTraceContext.create());
        final BraveTracer tracer = new BraveTracer(tracing.tracer(), tracingContext);

        // doesn't throw
        try (Scope scope = tracer.currentTraceContext().newScope(null)) {
            assertThat(scope).isNotNull();
        }

        // throws
        try (SpanInScope scope = tracer.withSpan(null)) {
            assertThat(scope).isNotNull();
        }
    }
}
