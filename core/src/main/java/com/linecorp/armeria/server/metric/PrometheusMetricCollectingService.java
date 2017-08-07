/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.metric;

import java.util.Map;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MetricLabel;
import com.linecorp.armeria.internal.metric.PrometheusMetricRequestDecorator;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import io.prometheus.client.CollectorRegistry;

/**
 * Decorates a {@link Service} to collect metrics into Prometheus {@link CollectorRegistry}.
 * To use, simply prepare a {@link CollectorRegistry} and add this decorator to a service specification.
 * @param <T> {@link MetricLabel} type
 * @param <I> {@link Request} type
 * @param <O> {@link Response} type
 */
public final class PrometheusMetricCollectingService
        <T extends MetricLabel<T>, I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {
    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Wrapped Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction Must return a sorted map in natural order
     * @return A service decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    Function<Service<I, O>, PrometheusMetricCollectingService<T, I, O>>
    newDecorator(CollectorRegistry collectorRegistry,
                 T[] metricLabels,
                 Function<RequestLog, Map<T, String>> labelingFunction) {
        PrometheusMetricRequestDecorator<T, I, O> decoratingServiceFunction =
                PrometheusMetricRequestDecorator.decorateService(collectorRegistry, metricLabels,
                                                                 labelingFunction);
        return service -> new PrometheusMetricCollectingService<>(service, decoratingServiceFunction);
    }

    /**
     * Returns a new {@link Service} decorator that tracks request stats using the Prometheus metrics library.
     *
     * @param <I> Request type
     * @param <O> Response type
     * @param collectorRegistry Wrapped Prometheus registry
     * @param metricLabels Labels names for metrics
     * @param labelingFunction Must return a sorted map in natural order
     * @return A service decorator function
     */
    public static <T extends MetricLabel<T>, I extends Request, O extends Response>
    Function<Service<I, O>, PrometheusMetricCollectingService<T, I, O>>
    newDecorator(CollectorRegistry collectorRegistry,
                 Iterable<T> metricLabels,
                 Function<RequestLog, Map<T, String>> labelingFunction) {
        PrometheusMetricRequestDecorator<T, I, O> decoratingServiceFunction =
                PrometheusMetricRequestDecorator.decorateService(collectorRegistry, metricLabels,
                                                                 labelingFunction);
        return service -> new PrometheusMetricCollectingService<>(service, decoratingServiceFunction);
    }

    private final PrometheusMetricRequestDecorator<T, I, O> requestDecorator;

    private PrometheusMetricCollectingService(Service<I, O> delegate,
                                              PrometheusMetricRequestDecorator<T, I, O> requestDecorator) {
        super(delegate);
        this.requestDecorator = requestDecorator;
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        return requestDecorator.serve(delegate(), ctx, req);
    }
}
