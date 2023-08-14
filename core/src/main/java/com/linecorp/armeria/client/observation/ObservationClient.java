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

package com.linecorp.armeria.client.observation;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.observation.HttpClientObservationDocumentation.Events;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.observation.ObservationService;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

/**
 * Decorates an {@link HttpClient} to trace outbound {@link HttpRequest}s using
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
 * WebClient.builder()
 *          .decorator(ObservationClient.newDecorator(observationRegistry))
 * ...
 * }</pre>
 *
 * @see ObservationService
 */
@UnstableApi
public final class ObservationClient extends SimpleDecoratingHttpClient {

    /**
     * Creates a new micrometer observation integrated {@link HttpClient} decorator
     * using the specified {@link ObservationRegistry}.
     */
    public static Function<? super HttpClient, ObservationClient> newDecorator(
            ObservationRegistry observationRegistry) {
        requireNonNull(observationRegistry, "observationRegistry");
        return delegate -> new ObservationClient(delegate, observationRegistry, null);
    }

    /**
     * Creates a new micrometer observation integrated {@link HttpClient} decorator
     * using the specified {@link ObservationRegistry} and {@link ObservationConvention}.
     */
    public static Function<? super HttpClient, ObservationClient> newDecorator(
            ObservationRegistry observationRegistry,
            ObservationConvention<ClientObservationContext> observationConvention) {
        requireNonNull(observationRegistry, "observationRegistry");
        requireNonNull(observationConvention, "observationConvention");
        return delegate -> new ObservationClient(delegate, observationRegistry,
                                                 observationConvention);
    }

    private final ObservationRegistry observationRegistry;

    @Nullable
    private final ObservationConvention<ClientObservationContext> httpClientObservationConvention;

    private ObservationClient(
            HttpClient delegate, ObservationRegistry observationRegistry,
            @Nullable ObservationConvention<ClientObservationContext> httpClientObservationConvention) {
        super(delegate);
        this.observationRegistry = requireNonNull(observationRegistry, "observationRegistry");
        this.httpClientObservationConvention = httpClientObservationConvention;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        final ClientObservationContext clientObservationContext =
                new ClientObservationContext(ctx, newHeaders, req);
        final Observation observation = HttpClientObservationDocumentation.OBSERVATION.observation(
                httpClientObservationConvention, DefaultHttpClientObservationConvention.INSTANCE,
                () -> clientObservationContext, observationRegistry).start();
        final HttpRequest newReq = req.withHeaders(newHeaders);
        ctx.updateRequest(newReq);
        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);

        if (observationRegistry.isNoop() || observation.isNoop()) {
            return unwrap().execute(ctx, newReq);
        }

        if (ctxExtension != null) {
            // Make the span the current span and run scope decorators when the ctx is pushed.
            ctxExtension.hook(observation::openScope);
        }

        enrichObservation(ctx, clientObservationContext, observation);

        return observation.scopedChecked(() -> unwrap().execute(ctx, newReq));
    }

    private static void enrichObservation(ClientRequestContext ctx,
                                          ClientObservationContext clientObservationContext,
                                          Observation observation) {
        ctx.log()
           .whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(Events.WIRE_SEND));

        ctx.log()
           .whenAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> {
               if (requestLog.responseFirstBytesTransferredTimeNanos() != null) {
                   observation.event(Events.WIRE_RECEIVE);
               }
           });

        ctx.log().whenComplete()
           .thenAccept(requestLog -> {
               // TODO: ClientConnectionTimings - there is no way to record events
               // with a specific timestamp for an observation
               clientObservationContext.setResponse(requestLog);
               observation.stop();
           });
    }
}
