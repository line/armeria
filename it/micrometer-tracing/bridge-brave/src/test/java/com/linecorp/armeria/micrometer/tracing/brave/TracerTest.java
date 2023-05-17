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

import org.junit.jupiter.api.Test;

import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.context.slf4j.MDCScopeDecorator;
import brave.handler.SpanHandler;
import brave.propagation.B3Propagation;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;

class TracerTest {

    @Test
    void testAsdf() {
        final SpanHandler spanHandler = SpanHandler.NOOP;
        final ThreadLocalCurrentTraceContext currentTraceContext =
                ThreadLocalCurrentTraceContext.newBuilder()
                                              .addScopeDecorator(MDCScopeDecorator.get())
                                              .build();
        final CurrentTraceContext bridgeContext = new BraveCurrentTraceContext(currentTraceContext);
        final Tracing tracing =
                Tracing.newBuilder()
                       .currentTraceContext(currentTraceContext)
                       .supportsJoin(false)
                       .traceId128Bit(true)
                       .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
                                                             .add(BaggagePropagationConfig.SingleBaggageField.remote(
                                                                     BaggageField.create("from_span_in_scope 1")))
                                                             .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span_in_scope 2")))
                                                             .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span")))
                                                             .build())
                       .sampler(Sampler.ALWAYS_SAMPLE)
                       .addSpanHandler(spanHandler)
                       .build();
        final brave.Tracer braveTracer = tracing.tracer();
        final Tracer tracer = new BraveTracer(braveTracer, bridgeContext, new BraveBaggageManager());

        final Span newSpan = tracer.nextSpan().name("calculateText");
        try (Tracer.SpanInScope ws = tracer.withSpan(newSpan.start())) {
            newSpan.tag("taxValue", "123");
            newSpan.event("taxCalculated");
        } finally {
            newSpan.end();
        }
    }
}
