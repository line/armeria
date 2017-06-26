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

package com.linecorp.armeria.client.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.metric.DropwizardMetricCollector;

/**
 * Decorates a {@link Client} to collect metrics into Dropwizard {@link MetricRegistry}.
 * To use, simply prepare a {@link MetricRegistry} and add this decorator to a client.
 *
 * <p>Example:
 * <pre>{@code
 * MetricRegistry metricRegistry = new MetricRegistry();
 * MyService.Iface client = new ClientBuilder(uri)
 *         .decorate(DropwizardMetricCollectingClient.newDecorator(
 *                 metricRegistry, MetricRegistry.name("clients", "myService")))
 *         .build(MyService.Iface.class);
 * }
 * </pre>
 *
 * <p>It is generally recommended to define your own name for the service instead of using something like
 * the Java class to make sure otherwise safe changes like renames don't break metrics.
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public final class DropwizardMetricCollectingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    /**
     * Returns a {@link Client} decorator that tracks request stats using the Dropwizard metrics library.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNameFunc the function that transforms a {@link RequestLog} into a metric name
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, DropwizardMetricCollectingClient<I, O>> newDecorator(
            MetricRegistry metricRegistry,
            Function<? super RequestLog, String> metricNameFunc) {

        requireNonNull(metricRegistry, "metricRegistry");
        requireNonNull(metricNameFunc, "metricNameFunc");

        return client -> new DropwizardMetricCollectingClient<>(client, metricRegistry, metricNameFunc);
    }

    /**
     * Returns a {@link Client} decorator that tracks request stats using the Dropwizard metrics library.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNamePrefix the prefix of the names of the metrics created by the returned decorator.
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, DropwizardMetricCollectingClient<I, O>> newDecorator(
            MetricRegistry metricRegistry, String metricNamePrefix) {

        requireNonNull(metricNamePrefix, "metricNamePrefix");
        return newDecorator(metricRegistry, log -> defaultMetricName(log, metricNamePrefix));
    }

    private static String defaultMetricName(RequestLog log, String metricNamePrefix) {
        String methodName = null;

        final Object envelope = log.requestHeaders();
        final Object content = log.requestContent();
        if (envelope instanceof HttpHeaders) {
            methodName = ((HttpHeaders) envelope).method().name();
        }

        if (content instanceof RpcRequest) {
            methodName = ((RpcRequest) content).method();
        }

        if (methodName == null) {
            methodName = MoreObjects.firstNonNull(log.method().name(), "__UNKNOWN_METHOD__");
        }

        return MetricRegistry.name(metricNamePrefix, methodName);
    }

    private final DropwizardMetricCollector collector;

    @SuppressWarnings("unchecked")
    DropwizardMetricCollectingClient(
            Client<I, O> delegate,
            MetricRegistry metricRegistry,
            Function<? super RequestLog, String> metricNameFunc) {
        super(delegate);
        collector = new DropwizardMetricCollector(
                metricRegistry, (Function<RequestLog, String>) metricNameFunc);
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        ctx.log().addListener(collector::onRequestStart,
                              RequestLogAvailability.REQUEST_HEADERS,
                              RequestLogAvailability.REQUEST_CONTENT);
        ctx.log().addListener(collector::onRequestEnd,
                              RequestLogAvailability.REQUEST_END);
        ctx.log().addListener(collector::onResponse,
                              RequestLogAvailability.COMPLETE);

        return delegate().execute(ctx, req);
    }
}
