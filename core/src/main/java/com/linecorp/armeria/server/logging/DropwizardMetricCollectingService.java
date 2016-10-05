/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.logging.DropwizardMetricConsumer;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates a {@link Service} to collect metrics into Dropwizard {@link MetricRegistry}.
 * To use, simply prepare a {@link MetricRegistry} and add this decorator to a service specification.
 *
 * <p>Example:
 * <pre>{@code
 * MetricRegistry metricRegistry = new MetricRegistry();
 * serverBuilder.serviceAt(
 *         "/service",
 *         ThriftService.of(handler).decorate(
 *                 DropwizardMetricCollectingService.newDecorator(metricRegistry, "services")));
 * }
 * </pre>
 *
 * <p>It is generally recommended to define your own name for the service instead of using something like
 * the Java class to make sure otherwise safe changes like renames don't break metrics.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class DropwizardMetricCollectingService<I extends Request, O extends Response>
        extends LogCollectingService<I, O> {

    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Dropwizard metrics
     * library.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNameFunc the function that transforms a {@link RequestLog} into a metric name
     */
    public static <I extends Request, O extends Response>
    Function<Service<? super I, ? extends O>, DropwizardMetricCollectingService<I, O>> newDecorator(
            MetricRegistry metricRegistry,
            BiFunction<? super ServiceRequestContext, ? super RequestLog, String> metricNameFunc) {

        requireNonNull(metricRegistry, "metricRegistry");
        requireNonNull(metricNameFunc, "metricNameFunc");

        return service -> new DropwizardMetricCollectingService<>(
                service, metricRegistry, metricNameFunc);
    }

    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Dropwizard metrics
     * library.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNamePrefix the prefix of the names of the metrics created by the returned decorator.
     */
    public static <I extends Request, O extends Response>
    Function<Service<? super I, ? extends O>, DropwizardMetricCollectingService<I, O>> newDecorator(
            MetricRegistry metricRegistry, String metricNamePrefix) {

        requireNonNull(metricNamePrefix, "metricNamePrefix");
        return newDecorator(metricRegistry, (ctx, req) -> defaultMetricName(ctx, req, metricNamePrefix));
    }

    private static String defaultMetricName(
            ServiceRequestContext ctx, RequestLog req, String metricNamePrefix) {

        String pathAsMetricName = null;
        String methodName = null;

        if (req.hasAttr(RequestLog.HTTP_HEADERS)) {
            final HttpHeaders headers = req.attr(RequestLog.HTTP_HEADERS).get();
            pathAsMetricName = ctx.pathMapping().metricName();
            methodName = headers.method().name();
        }

        if (req.hasAttr(RequestLog.RPC_REQUEST)) {
            methodName = req.attr(RequestLog.RPC_REQUEST).get().method();
        }

        pathAsMetricName = MoreObjects.firstNonNull(pathAsMetricName, "__UNKNOWN_PATH__");

        if (methodName == null) {
            methodName = MoreObjects.firstNonNull(req.method(), "__UNKNOWN_METHOD__");
        }

        return MetricRegistry.name(metricNamePrefix, pathAsMetricName, methodName);
    }

    @SuppressWarnings("unchecked")
    DropwizardMetricCollectingService(
            Service<? super I, ? extends O> delegate,
            MetricRegistry metricRegistry,
            BiFunction<? super ServiceRequestContext, ? super RequestLog, String> metricNameFunc) {

        super(delegate, new DropwizardMetricConsumer(
                metricRegistry, (BiFunction<RequestContext, RequestLog, String>) metricNameFunc));
    }
}
