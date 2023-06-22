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

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.observation.HttpClientObservationDocumentation.Events;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.RequestContextExtension;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Decorates an {@link HttpClient} to trace outbound {@link HttpRequest}s using
 * <a href="https://github.com/micrometer-metrics/micrometer">Micrometer Observation</a>.
 */
public final class MicrometerObservationClient extends SimpleDecoratingHttpClient {

    /**
     * Creates a new tracing {@link HttpClient} decorator
     * using the specified {@link ObservationRegistry} instance.
     */
    public static Function<? super HttpClient, MicrometerObservationClient> newDecorator(
            ObservationRegistry observationRegistry) {
        return delegate -> new MicrometerObservationClient(delegate, observationRegistry);
    }

    /**
     * Creates a new tracing {@link HttpClient} decorator
     * using the specified {@link ObservationRegistry}
     * and {@link HttpClientObservationConvention} instances.
     */
    public static Function<? super HttpClient, MicrometerObservationClient> newDecorator(
            ObservationRegistry observationRegistry,
            HttpClientObservationConvention httpClientObservationConvention) {
        return delegate -> new MicrometerObservationClient(delegate, observationRegistry,
                                                           httpClientObservationConvention);
    }

    private final ObservationRegistry observationRegistry;

    @Nullable
    private final HttpClientObservationConvention httpClientObservationConvention;

    /**
     * Creates a new instance.
     */
    private MicrometerObservationClient(HttpClient delegate, ObservationRegistry observationRegistry) {
        this(delegate, observationRegistry, null);
    }

    /**
     * Creates a new instance.
     */
    private MicrometerObservationClient(HttpClient delegate,
                                        ObservationRegistry observationRegistry,
                                        @Nullable HttpClientObservationConvention
                                                httpClientObservationConvention) {
        super(delegate);
        this.observationRegistry = observationRegistry;
        this.httpClientObservationConvention = httpClientObservationConvention;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        final HttpClientContext httpClientContext = new HttpClientContext(ctx, newHeaders, req);
        final Observation observation = HttpClientObservationDocumentation.OBSERVATION.observation(
                this.httpClientObservationConvention, DefaultHttpClientObservationConvention.INSTANCE,
                () -> httpClientContext, observationRegistry).start();
        final HttpRequest newReq = req.withHeaders(newHeaders);
        ctx.updateRequest(newReq);
        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);

        if (!observationRegistry.isNoop() && !observation.isNoop() && ctxExtension != null) {
            // Make the span the current span and run scope decorators when the ctx is pushed.
            ctxExtension.hook(observation::openScope);
        }

        enrichObservation(ctx, httpClientContext, observation);

        return observation.scopedChecked(() -> unwrap().execute(ctx, newReq));
        // TODO: Maybe we should move the observation stopping here?
    }

    private void enrichObservation(ClientRequestContext ctx, HttpClientContext httpClientContext,
                                   Observation observation) {
        if (observation.isNoop()) {
            // For no-op spans, we only need to inject into headers and don't set any other attributes.
            return;
        }

        ctx.log()
           .whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(Events.WIRE_SEND));

        ctx.log()
           .whenAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(Events.WIRE_RECEIVE));

        ctx.log().whenComplete()
           .thenAccept(requestLog -> {
               // TODO: ClientConnectionTimings - no hook to be there
               //  at the moment of those things actually hapening
               httpClientContext.setResponse(requestLog);
               observation.stop();
           });
    }
}
