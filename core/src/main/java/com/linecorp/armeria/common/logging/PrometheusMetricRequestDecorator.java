/**
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

package com.linecorp.armeria.common.logging;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClientFunction;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.prometheus.client.Collector;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public final class PrometheusMetricRequestDecorator<I extends Request, O extends Response>
        implements DecoratingServiceFunction<I, O>, DecoratingClientFunction<I, O> {

    private static class MetricAdapter {
        private AtomicReference<MetricAdapter> initialized;

        private Summary timer;
        private Counter success;
        private Counter failure;
        private Gauge activeRequests;
        private Summary requestBytes;
        private Summary responseBytes;

        private List<Method> metricLabelValueMethods;

        public MetricAdapter() {
            initialized = new AtomicReference<>();
        }

        public MetricAdapter initialize(MetricLabel<?> namingFunction,
                                        PrometheusRegistryWrapper collectorRegistry) {
            if (initialized.compareAndSet(null, this)) {
                BeanInfo metricLabelValueBeanInfo;

                try {
                    metricLabelValueBeanInfo = Introspector.getBeanInfo(namingFunction.getValue().getClass());
                } catch (IntrospectionException e) {
                    throw new RuntimeException(e);
                }

                String[] labels = Arrays.asList(metricLabelValueBeanInfo.getPropertyDescriptors())
                                        .stream()
                                        .skip(1)
                                        .map(PropertyDescriptor::getName)
                                        .map(Collector::sanitizeMetricName)
                                        .toArray(String[]::new);

                String sanitizedName = namingFunction.getTokenizedName()
                                                     .stream()
                                                     .collect(Collectors.joining("_"));

                metricLabelValueMethods = Arrays.asList(metricLabelValueBeanInfo.getPropertyDescriptors())
                                                .stream()
                                                .skip(1)
                                                .map(PropertyDescriptor::getReadMethod)
                                                .collect(Collectors.toList());

                timer = create(sanitizedName + "_timer",
                               collectorRegistry,
                               name -> Summary.build()
                                              .quantile(.5, .01)
                                              .quantile(.75, .01)
                                              .quantile(.95, .01)
                                              .quantile(.98, .01)
                                              .quantile(.99, .01)
                                              .quantile(.999, .01)
                                              .name(name)
                                              .labelNames(labels)
                                              .help(namingFunction.getDescription())
                                              .create());
                success = create(sanitizedName + "_success",
                                 collectorRegistry,
                                 name -> Counter.build().name(name)
                                                .labelNames(labels)
                                                .help(namingFunction.getDescription())
                                                .create());
                failure = create(sanitizedName + "_failure",
                                 collectorRegistry,
                                 name -> Counter.build().name(name)
                                                .labelNames(labels)
                                                .help(namingFunction.getDescription())
                                                .create());
                activeRequests = create(sanitizedName + "_activeRequests",
                                        collectorRegistry,
                                        name -> Gauge.build().name(name)
                                                     .labelNames(labels)
                                                     .help(namingFunction.getDescription())
                                                     .create());
                requestBytes = create(sanitizedName + "_requestBytes",
                                      collectorRegistry,
                                      name -> Summary.build()
                                                     .quantile(.5, .01)
                                                     .quantile(.75, .01)
                                                     .quantile(.95, .01)
                                                     .quantile(.98, .01)
                                                     .quantile(.99, .01)
                                                     .quantile(.999, .01)
                                                     .name(name)
                                                     .labelNames(labels)
                                                     .help(namingFunction.getDescription())
                                                     .create());
                responseBytes = create(sanitizedName + "_responseBytes",
                                       collectorRegistry,
                                       name -> Summary.build()
                                                      .quantile(.5, .01)
                                                      .quantile(.75, .01)
                                                      .quantile(.95, .01)
                                                      .quantile(.98, .01)
                                                      .quantile(.99, .01)
                                                      .quantile(.999, .01)
                                                      .name(name)
                                                      .labelNames(labels)

                                                      .help(namingFunction.getDescription())
                                                      .create());

            }
            return this;
        }

        private <T extends Collector> T create(String name,
                                               PrometheusRegistryWrapper collectorRegistry,
                                               Function<String, T> ifAbsent) {
            return collectorRegistry.create(name, ifAbsent);
        }

        private <T> String[] getValues(MetricLabel<T> label) {
            BiFunction<Method, T, Object> callMethod = (method, obj) -> {
                try {
                    return method.invoke(obj);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            };

            return metricLabelValueMethods.stream()
                                          .map(method -> callMethod.apply(method, label.getValue()))
                                          .map(Objects::toString)
                                          .toArray(String[]::new);
        }

        public MetricAdapter time(MetricLabel<?> label, long time) throws Exception {
            check(timer).labels(getValues(label)).observe(time);
            return this;
        }

        public MetricAdapter success(MetricLabel<?> label) {
            check(success).labels(getValues(label)).inc();
            return this;
        }

        public MetricAdapter failure(MetricLabel<?> label) {
            check(failure).labels(getValues(label)).inc();
            return this;
        }

        public MetricAdapter incActiveRequests(MetricLabel<?> label) {
            check(activeRequests).labels(getValues(label)).inc();
            return this;
        }

        public MetricAdapter decActiveRequests(MetricLabel<?> label) {
            check(activeRequests).labels(getValues(label)).dec();
            return this;
        }

        public MetricAdapter requestBytes(MetricLabel<?> label, RequestLog log) {
            check(requestBytes).labels(getValues(label)).observe(log.requestLength());
            return this;
        }

        public MetricAdapter responseBytes(MetricLabel<?> label, RequestLog log) {
            check(responseBytes).labels(getValues(label)).observe(log.responseLength());
            return this;
        }

        private <T> T check(T metric) {
            if (Objects.isNull(metric)) {
                throw new IllegalStateException(MetricAdapter.class.getCanonicalName() + "not initialized");
            }
            return metric;
        }
    }

    private PrometheusRegistryWrapper collectorRegistry;
    private Function<RequestLog, MetricLabel> labelingFunction;
    private MetricAdapter metricAdapter;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    private PrometheusMetricRequestDecorator(PrometheusRegistryWrapper collectorRegistry,
                                             Function<RequestLog, MetricLabel> labelingFunction) {
        this.collectorRegistry = collectorRegistry;
        this.labelingFunction = labelingFunction;
        this.metricAdapter = new MetricAdapter();
    }

    @FunctionalInterface
    private interface ThrowsBiFunction<C, I, O> {
        O apply(C c, I i) throws Exception;
    }

    private <C extends RequestContext> O request(ThrowsBiFunction<C, I, O> function, C ctx, I req)
            throws Exception {
        ctx.log().addListener(
                log -> {
                    MetricLabel<?> label = labelingFunction.apply(log);
                    metricAdapter.initialize(label, collectorRegistry)
                                 .incActiveRequests(label);
                }, RequestLogAvailability.REQUEST_ENVELOPE, RequestLogAvailability.REQUEST_CONTENT);

        ctx.log().addListener(
                log -> {
                    MetricLabel<?> label = labelingFunction.apply(log);
                    metricAdapter.initialize(label, collectorRegistry)
                                 .requestBytes(label, log);
                    if (log.requestCause() != null) {
                        metricAdapter.failure(label)
                                     .decActiveRequests(label);
                    }
                }, RequestLogAvailability.REQUEST_END);

        ctx.log().addListener(
                log -> {
                    if (log.requestCause() != null) {
                        return;
                    }
                    MetricLabel<?> label = labelingFunction.apply(log);
                    metricAdapter.initialize(label, collectorRegistry)
                                 .time(label, log.totalDurationNanos())
                                 .responseBytes(label, log)
                                 .decActiveRequests(label);

                    if (isSuccess(log)) {
                        metricAdapter.success(label);
                    } else {
                        metricAdapter.failure(label);
                    }
                }, RequestLogAvailability.COMPLETE);
        return function.apply(ctx, req);
    }

    @Override
    public O serve(Service<I, O> delegate, ServiceRequestContext ctx, I req) throws Exception {
        return request(delegate::serve, ctx, req);
    }

    @Override
    public O execute(Client<I, O> delegate, ClientRequestContext ctx, I req) throws Exception {
        return request(delegate::execute, ctx, req);
    }

    public static <I extends Request, O extends Response> DecoratingServiceFunction<I, O> decorateService(
            PrometheusRegistryWrapper collectorRegistry,
            Function<RequestLog, MetricLabel> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry, labelingFunction);
    }

    public static <I extends Request, O extends Response> DecoratingClientFunction<I, O> decorateClient(
            PrometheusRegistryWrapper collectorRegistry,
            Function<RequestLog, MetricLabel> labelingFunction) {
        return new PrometheusMetricRequestDecorator<>(collectorRegistry, labelingFunction);
    }

    private static boolean isSuccess(RequestLog log) {
        if (log.responseCause() != null) {
            return false;
        }

        if (HttpSessionProtocols.isHttp(log.sessionProtocol())) {
            if (log.statusCode() >= 400) {
                return false;
            }
        } else {
            if (log.statusCode() != 0) {
                return false;
            }
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            return !((RpcResponse) responseContent).isCompletedExceptionally();
        }

        return true;
    }
}
