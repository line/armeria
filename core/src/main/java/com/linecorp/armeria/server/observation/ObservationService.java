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

package com.linecorp.armeria.server.observation;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Function;

import com.linecorp.armeria.client.observation.ObservationClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

/**
 * Decorates an {@link HttpService} to trace inbound {@link HttpRequest}s using
 * <a href="https://github.com/micrometer-metrics/micrometer">Micrometer Observation</a>.
 * The following may be a typical implementation using a brave implementation:
 * <pre>{@code
 * // create a tracer
 * BraveCurrentTraceContext braveCurrentTraceContext = new BraveCurrentTraceContext(
 *   tracing.currentTraceContext());
 * BravePropagator bravePropagator = new BravePropagator(tracing);
 * BraveTracer braveTracer = new BraveTracer(tracing.tracer(), braveCurrentTraceContext,
 *                                           new BraveBaggageManager());
 *
 * // add tracing handlers
 * List<TracingObservationHandler<?>> tracingHandlers =
 *   Arrays.asList(new PropagatingSenderTracingObservationHandler<>(braveTracer, bravePropagator),
 *                 new PropagatingReceiverTracingObservationHandler<>(braveTracer, bravePropagator),
 *                 new DefaultTracingObservationHandler(braveTracer));
 *
 * // create a registry
 * ObservationRegistry observationRegistry = ObservationRegistry.create();
 *
 * // add the tracing handlers
 * observationRegistry.observationConfig().observationHandler(
 *         new FirstMatchingCompositeObservationHandler(tracingHandlers));
 *
 * // add the decorator
 * Server.builder()
 *       .decorator(ObservationService.newDecorator(observationRegistry))
 * ...
 * }</pre>
 *
 * @see ObservationClient
 */
@UnstableApi
public final class ObservationService extends SimpleDecoratingHttpService {

    /**
     * Creates a new micrometer observation integrated {@link HttpService} decorator using the
     * specified {@link ObservationRegistry} instance.
     */
    public static Function<? super HttpService, ObservationService>
    newDecorator(ObservationRegistry observationRegistry) {
        requireNonNull(observationRegistry, "observationRegistry");
        return service -> new ObservationService(service, observationRegistry, null);
    }

    /**
     * Creates a new micrometer observation integrated {@link HttpService} decorator using the
     * specified {@link ObservationRegistry} and {@link ObservationConvention}.
     */
    public static Function<? super HttpService, ObservationService>
    newDecorator(ObservationRegistry observationRegistry,
                 ObservationConvention<ServiceObservationContext> observationConvention) {
        requireNonNull(observationRegistry, "observationRegistry");
        requireNonNull(observationConvention, "observationConvention");
        return service -> new ObservationService(
                service, observationRegistry, observationConvention);
    }

    private final ObservationRegistry observationRegistry;
    @Nullable
    private final ObservationConvention<ServiceObservationContext> observationConvention;

    private ObservationService(
            HttpService delegate, ObservationRegistry observationRegistry,
            @Nullable ObservationConvention<ServiceObservationContext> observationConvention) {
        super(delegate);
        this.observationRegistry = requireNonNull(observationRegistry, "observationRegistry");
        this.observationConvention = observationConvention;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Set<TransientServiceOption> transientServiceOptions = ctx.config().transientServiceOptions();
        if (!transientServiceOptions.contains(TransientServiceOption.WITH_TRACING) ||
            !transientServiceOptions.contains(TransientServiceOption.WITH_METRIC_COLLECTION)) {
            return unwrap().serve(ctx, req);
        }

        final ServiceObservationContext serviceObservationContext = new ServiceObservationContext(ctx, req);
        final Observation observation = HttpServiceObservationDocumentation.OBSERVATION.observation(
                observationConvention, DefaultServiceObservationConvention.INSTANCE,
                () -> serviceObservationContext, observationRegistry).start();

        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);

        if (observationRegistry.isNoop() || observation.isNoop()) {
            return unwrap().serve(ctx, req);
        }

        if (ctxExtension != null) {
            // Make the span the current span and run scope decorators when the ctx is pushed.
            ctxExtension.hook(observation::openScope);
        }

        enrichObservation(ctx, serviceObservationContext, observation);

        return observation.scopedChecked(() -> unwrap().serve(ctx, req));
    }

    private static void enrichObservation(ServiceRequestContext ctx,
                                          ServiceObservationContext serviceObservationContext,
                                          Observation observation) {
        ctx.log()
           .whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(
                   HttpServiceObservationDocumentation.Events.WIRE_RECEIVE));

        ctx.log()
           .whenAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> {
               if (requestLog.responseFirstBytesTransferredTimeNanos() != null) {
                   observation.event(HttpServiceObservationDocumentation.Events.WIRE_SEND);
               }
           });

        ctx.log().whenComplete()
           .thenAccept(requestLog -> {
               serviceObservationContext.setResponse(requestLog);
               observation.stop();
           });
    }
}
