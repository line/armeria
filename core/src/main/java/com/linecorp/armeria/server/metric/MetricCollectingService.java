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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.metric.RequestMetricSupport;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

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
        return delegate -> new MetricCollectingService(delegate, meterIdPrefixFunction);
    }

    private final MeterIdPrefixFunction meterIdPrefixFunction;

    MetricCollectingService(HttpService delegate, MeterIdPrefixFunction meterIdPrefixFunction) {
        super(delegate);
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, true);
        return delegate().serve(ctx, req);
    }
}
