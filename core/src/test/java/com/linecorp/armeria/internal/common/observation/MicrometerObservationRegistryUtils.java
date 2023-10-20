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

package com.linecorp.armeria.internal.common.observation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import brave.Tracing;
import brave.http.HttpTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.observation.MeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;

public final class MicrometerObservationRegistryUtils {

    public static ObservationRegistry observationRegistry(HttpTracing httpTracing) {
        return observationRegistry(httpTracing.tracing());
    }

    public static ObservationRegistry observationRegistry(Tracing tracing) {
        final BraveCurrentTraceContext braveCurrentTraceContext = new BraveCurrentTraceContext(
                tracing.currentTraceContext());
        final BravePropagator bravePropagator = new BravePropagator(tracing);
        final BraveTracer braveTracer = new BraveTracer(tracing.tracer(), braveCurrentTraceContext,
                                                        new BraveBaggageManager());
        final List<TracingObservationHandler<?>> tracingHandlers =
                Arrays.asList(new PropagatingSenderTracingObservationHandler<>(braveTracer, bravePropagator),
                              new PropagatingReceiverTracingObservationHandler<>(braveTracer, bravePropagator),
                              new DefaultTracingObservationHandler(braveTracer));

        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final List<MeterObservationHandler<?>> meterHandlers = Collections.singletonList(
                new DefaultMeterObservationHandler(meterRegistry));

        final ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        tracingHandlers));
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        meterHandlers));
        return observationRegistry;
    }

    private MicrometerObservationRegistryUtils() {}
}
