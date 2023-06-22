/*
 * Copyright 2019 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Decorates an {@link HttpService} to trace inbound {@link HttpRequest}s using
 * <a href="https://github.com/micrometer-metrics/micrometer">Micrometer Observation</a>.
 */
public final class MicrometerObservationService extends SimpleDecoratingHttpService {

    /**
     * Creates a new tracing {@link HttpService} decorator using the
     * specified {@link ObservationRegistry} instance.
     */
    public static Function<? super HttpService, MicrometerObservationService>
    newDecorator(ObservationRegistry observationRegistry) {
        return service -> new MicrometerObservationService(service, observationRegistry);
    }

    private final ObservationRegistry observationRegistry;

    @Nullable
    private final ServiceObservationConvention serviceObservationConvention;

    /**
     * Creates a new instance.
     */
    private MicrometerObservationService(HttpService delegate, ObservationRegistry observationRegistry) {
        this(delegate, observationRegistry, null);
    }

    /**
     * Creates a new instance.
     */
    private MicrometerObservationService(HttpService delegate, ObservationRegistry observationRegistry,
                                         @Nullable ServiceObservationConvention serviceObservationConvention) {
        super(delegate);
        this.observationRegistry = observationRegistry;
        this.serviceObservationConvention = serviceObservationConvention;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        // TODO: What about this?
        if (!ctx.config().transientServiceOptions().contains(TransientServiceOption.WITH_TRACING) &&
            !ctx.config().transientServiceOptions().contains(
                    TransientServiceOption.WITH_METRIC_COLLECTION)) {
            return unwrap().serve(ctx, req);
        }

        final HttpServerContext httpServerContext = new HttpServerContext(ctx, req);
        final Observation observation = ServiceObservationDocumentation.OBSERVATION.observation(
                this.serviceObservationConvention, DefaultServiceObservationConvention.INSTANCE,
                () -> httpServerContext, observationRegistry).start();

        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (!observationRegistry.isNoop() && !observation.isNoop() && ctxExtension != null) {
            // Make the span the current span and run scope decorators when the ctx is pushed.
            ctxExtension.hook(observation::openScope);
        }

        enrichObservation(ctx, httpServerContext, observation);

        return observation.scopedChecked(
                () -> unwrap().serve(ctx, req)); // TODO: Maybe we should observation stopping here
    }

    private void enrichObservation(ServiceRequestContext ctx, HttpServerContext httpServerContext,
                                   Observation observation) {
        if (observation.isNoop()) {
            // For no-op spans, we only need to inject into headers and don't set any other attributes.
            return;
        }

        ctx.log()
           .whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(ServiceObservationDocumentation.Events.WIRE_RECEIVE));

        ctx.log()
           .whenAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)
           .thenAccept(requestLog -> observation.event(ServiceObservationDocumentation.Events.WIRE_SEND));

        ctx.log().whenComplete()
           .thenAccept(requestLog -> {
               httpServerContext.setResponse(requestLog);
               observation.stop();
               // TODO: ClientConnectionTimings - no hook to be there at the
               //  moment of those things actually hapenning
           });
    }
}
