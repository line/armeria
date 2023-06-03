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
package com.linecorp.armeria.server.metric;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.metric.RequestMetricSupport;
import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeKey;

/**
 * Decorates an {@link HttpService} to collect metrics into {@link MeterRegistry}.
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
 */
public final class MetricCollectingService extends SimpleDecoratingHttpService {

    // A variable to make sure setup method is not called twice.
    private static final AttributeKey<Boolean> REQUEST_METRICS_SET =
            AttributeKey.valueOf(MetricCollectingService.class, "REQUEST_METRICS_SET");

    /**
     * Returns a new {@link HttpService} decorator that tracks request stats using {@link MeterRegistry}.
     */
    public static Function<? super HttpService, MetricCollectingService> newDecorator(
            MeterIdPrefixFunction meterIdPrefixFunction) {

        requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        return builder(meterIdPrefixFunction).newDecorator();
    }

    /**
     * Returns a newly created {@link MetricCollectingServiceBuilder}.
     */
    public static MetricCollectingServiceBuilder builder(MeterIdPrefixFunction meterIdPrefixFunction) {
        return new MetricCollectingServiceBuilder(meterIdPrefixFunction);
    }

    private final MeterIdPrefixFunction meterIdPrefixFunction;
    @Nullable
    private final BiPredicate<? super RequestContext, ? super RequestLog> successFunction;
    private final ConcurrentMap<Route, Boolean> routeCache = new ConcurrentHashMap<>();

    MetricCollectingService(HttpService delegate,
                            MeterIdPrefixFunction meterIdPrefixFunction,
                            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        super(delegate);
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        this.successFunction = successFunction;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (shouldRecordMetrics(ctx)) {
            RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, true,
                                       successFunction != null ? successFunction::test
                                                               : ctx.config().successFunction());
        }
        return unwrap().serve(ctx, req);
    }

    private boolean shouldRecordMetrics(ServiceRequestContext ctx) {
        if (!ctx.config().transientServiceOptions().contains(TransientServiceOption.WITH_METRIC_COLLECTION)) {
            return false;
        }

        final Route route = ctx.config().route();
        final Boolean cachedResult = routeCache.get(route);
        if (cachedResult != null) {
            return cachedResult;
        }

        // An inner `MetricCollectingService` takes precedence over an outer one. Delegate to the inner
        // `MetricCollectingService` if exists.
        final boolean shouldRecord;
        final Service<HttpRequest, HttpResponse> delegate = unwrap();
        if (delegate instanceof RouteDecoratingService) {
            // Can't use `.as(serviceClass)` because RouteDecoratingService is not a decorator but a service
            // that has a queue for the next decorator chains.
            shouldRecord = ((RouteDecoratingService) delegate).as(ctx, MetricCollectingService.class) == null;
        } else {
            // null if the current decorator is the closest MetricCollectingService to the service.
            shouldRecord = delegate.as(MetricCollectingService.class) == null;
        }

        routeCache.put(route, shouldRecord);
        return shouldRecord;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);
        // Server.reconfigure() may change services bound to routes.
        routeCache.clear();
    }
}
