/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.metric;

import static com.linecorp.armeria.internal.metric.PrometheusUtil.registerIfAbsent;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.MetricLabel;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

/**
 * Provides request decorator functions for server and client sides. Functions will record metrics into
 * Prometheus {@link CollectorRegistry}.
 * @param <T> {@link MetricLabel} type
 * @param <I> {@link Request} type
 * @param <O> {@link Response} type
 */
public final class PrometheusMetricRequestDecorator<T extends MetricLabel<T>,
        I extends Request, O extends Response> {

    private static final String HELP_STRING = "Armeria client/server request metric";

    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction Must return a sorted map in natural order
     * @return A service decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    PrometheusMetricRequestDecorator<T, I, O> decorateService(
            CollectorRegistry collectorRegistry,
            T[] metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry,
                                                      metricLabels,
                                                      labelingFunction,
                                                      "armeria_server");
    }

    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction Must return a sorted map in natural order
     * @return A service decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    PrometheusMetricRequestDecorator<T, I, O> decorateService(
            CollectorRegistry collectorRegistry,
            Iterable<T> metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry,
                                                      metricLabels,
                                                      labelingFunction,
                                                      "armeria_server");
    }

    /**
     * Returns a new {@link Client} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction map of labels to values
     * @return A client decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    PrometheusMetricRequestDecorator<T, I, O> decorateClient(
            CollectorRegistry collectorRegistry,
            T[] metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry,
                                                      metricLabels,
                                                      labelingFunction,
                                                      "armeria_client");
    }

    /**
     * Returns a new {@link Client} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction map of labels to values
     * @return A client decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    PrometheusMetricRequestDecorator<T, I, O> decorateClient(
            CollectorRegistry collectorRegistry,
            Iterable<T> metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry,
                                                      metricLabels,
                                                      labelingFunction,
                                                      "armeria_client");
    }

    private final Function<RequestLog, Map<T, String>> labelingFunction;
    private final MetricFacade<T> metricFacade;

    private PrometheusMetricRequestDecorator(
            CollectorRegistry collectorRegistry,
            T[] metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction,
            String prefix) {
        requireNonNull(collectorRegistry, "collectorRegistry");
        requireNonNull(metricLabels, "metricLabels");
        T[] metricLabelsCopy = Arrays.copyOf(metricLabels, metricLabels.length);
        for (T metricLabel : metricLabelsCopy) {
            requireNonNull(metricLabel, "metricLabels contains null");
        }
        requireNonNull(labelingFunction, "labelingFunction");
        requireNonNull(prefix, "prefix");

        this.labelingFunction = labelingFunction;
        metricFacade = new MetricFacade<>(collectorRegistry, metricLabelsCopy, prefix, HELP_STRING);
    }

    @SuppressWarnings({ "unchecked", "SuspiciousArrayCast" })
    private PrometheusMetricRequestDecorator(
            CollectorRegistry collectorRegistry,
            Iterable<T> metricLabels,
            Function<RequestLog, Map<T, String>> labelingFunction,
            String prefix) {
        this(collectorRegistry,
             (T[]) Iterables.toArray(requireNonNull(metricLabels, "metricLabels"), MetricLabel.class),
             labelingFunction,
             prefix);
    }

    private <C extends RequestContext> O request(ThrowingBiFunction<C, I, O> function, C ctx, I req)
            throws Exception {
        ctx.log().addListener(
                log -> {
                    final String[] values = getLabelValues(log);
                    metricFacade.incActiveRequests(values);
                }, RequestLogAvailability.REQUEST_HEADERS, RequestLogAvailability.REQUEST_CONTENT);

        ctx.log().addListener(
                log -> {
                    final String[] values = getLabelValues(log);
                    metricFacade.requestBytes(values, log);
                    if (log.requestCause() != null) {
                        metricFacade.failure(values)
                                    .decActiveRequests(values);
                    }
                }, RequestLogAvailability.REQUEST_END);

        ctx.log().addListener(
                log -> {
                    if (log.requestCause() != null) {
                        return;
                    }
                    final String[] values = getLabelValues(log);
                    metricFacade.seconds(values, TimeUnit.NANOSECONDS.toSeconds(log.totalDurationNanos()))
                                .responseBytes(values, log)
                                .decActiveRequests(values);

                    if (isSuccess(log)) {
                        metricFacade.success(values);
                    } else {
                        metricFacade.failure(values);
                    }
                }, RequestLogAvailability.COMPLETE);
        return function.apply(ctx, req);
    }

    public O serve(Service<I, O> delegate, ServiceRequestContext ctx, I req) throws Exception {
        return request(delegate::serve, ctx, req);
    }

    public O execute(Client<I, O> delegate, ClientRequestContext ctx, I req) throws Exception {
        return request(delegate::execute, ctx, req);
    }

    private String[] getLabelValues(RequestLog log) {
        return ImmutableSortedMap.copyOf(labelingFunction.apply(log))
                                 .values()
                                 .stream()
                                 .toArray(String[]::new);
    }

    private static boolean isSuccess(RequestLog log) {
        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            return !((RpcResponse) responseContent).isCompletedExceptionally();
        }

        if (log.responseCause() != null) {
            return false;
        }

        final int statusCode = log.statusCode();
        return statusCode >= 100 && statusCode < 400;
    }

    @FunctionalInterface
    private interface ThrowingBiFunction<C, I, O> {
        O apply(C c, I i) throws Exception;
    }

    private static final class MetricFacade<T extends MetricLabel<T>> {

        private final Summary timer;
        private final Counter success;
        private final Counter failure;
        private final Gauge activeRequests;
        private final Summary requestBytes;
        private final Summary responseBytes;

        private MetricFacade(CollectorRegistry collectorRegistry,
                             T[] metricLabels,
                             String namePrefix,
                             String help) {
            requireNonNull(collectorRegistry, "collectorRegistry");
            requireNonNull(metricLabels, "metricLabels");
            requireNonNull(namePrefix, "namePrefix");
            requireNonNull(help, "help");

            final String[] metricLabelNames = Stream.of(metricLabels)
                                                    .sorted()
                                                    .map(MetricLabel::name)
                                                    .toArray(String[]::new);
            timer = registerIfAbsent(collectorRegistry, namePrefix + "_request_duration_seconds",
                                     name -> newSummary(name, help, metricLabelNames));
            success = registerIfAbsent(collectorRegistry, namePrefix + "_request_success_total",
                                       name -> Counter.build(name, help)
                                                      .labelNames(metricLabelNames)
                                                      .create());
            failure = registerIfAbsent(collectorRegistry, namePrefix + "_request_failure_total",
                                       name -> Counter.build(name, help)
                                                      .labelNames(metricLabelNames)
                                                      .create());
            activeRequests = registerIfAbsent(collectorRegistry, namePrefix + "_request_active",
                                              name -> Gauge.build(name, help)
                                                           .labelNames(metricLabelNames)
                                                           .create());
            requestBytes = registerIfAbsent(collectorRegistry, namePrefix + "_request_size_bytes",
                                            name -> newSummary(name, help, metricLabelNames));
            responseBytes = registerIfAbsent(collectorRegistry, namePrefix + "_response_size_bytes",
                                             name -> newSummary(name, help, metricLabelNames));
        }

        private static Summary newSummary(String name, String help, String... metricLabelNames) {
            return Summary.build(name, help)
                          .labelNames(metricLabelNames)
                          .quantile(.5, .01)
                          .quantile(.75, .01)
                          .quantile(.95, .01)
                          .quantile(.98, .01)
                          .quantile(.99, .01)
                          .quantile(.999, .01)
                          .create();
        }

        private MetricFacade<T> seconds(String[] values, double seconds) throws Exception {
            timer.labels(values).observe(seconds);
            return this;
        }

        private MetricFacade<T> success(String[] values) {
            success.labels(values).inc();
            return this;
        }

        private MetricFacade<T> failure(String[] values) {
            failure.labels(values).inc();
            return this;
        }

        private MetricFacade<T> incActiveRequests(String[] values) {
            activeRequests.labels(values).inc();
            return this;
        }

        private MetricFacade<T> decActiveRequests(String[] values) {
            activeRequests.labels(values).dec();
            return this;
        }

        private MetricFacade<T> requestBytes(String[] values, RequestLog log) {
            requestBytes.labels(values).observe(log.requestLength());
            return this;
        }

        private MetricFacade<T> responseBytes(String[] values, RequestLog log) {
            responseBytes.labels(values).observe(log.responseLength());
            return this;
        }
    }
}
