/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.metric.RequestMetricSupport;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decorates a {@link Service} to collect metrics into {@link MeterRegistry}.
 *
 * <p>Example:
 * <pre>{@code
 * serverBuilder.service(
 *         "/service",
 *         THttpService.of(handler)
 *                     .decorate(MetricCollectingService.newDecorator(
 *                             MeterIdPrefixFunction.ofDefault("myService"))));
 * }
 * </pre>
 *
 * <p>It is generally recommended not to use a class or package name as a metric name, because otherwise
 * seemingly harmless refactoring such as rename may break metric collection.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class MetricCollectingService<I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {

    /**
     * Returns a new {@link Service} decorator that tracks request stats using {@link MeterRegistry}.
     */
    public static <I extends Request, O extends Response>
    Function<Service<I, O>, MetricCollectingService<I, O>> newDecorator(
            MeterIdPrefixFunction meterIdPrefixFunction) {

        requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        return delegate -> new MetricCollectingService<>(delegate, meterIdPrefixFunction);
    }

    private final MeterIdPrefixFunction meterIdPrefixFunction;

    MetricCollectingService(Service<I, O> delegate, MeterIdPrefixFunction meterIdPrefixFunction) {
        super(delegate);
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);
        return delegate().serve(ctx, req);
    }
}
