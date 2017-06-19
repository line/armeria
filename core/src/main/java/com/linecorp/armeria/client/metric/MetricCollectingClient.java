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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.metric.MetricKeyFunction;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.internal.metric.MetricCollectionSupport;

/**
 * Decorates a {@link Client} to collect metrics into {@link Metrics}.
 *
 * <p>Example:
 * <pre>{@code
 * MyService.Iface client = new ClientBuilder(uri)
 *         .decorator(HttpRequest.class, HttpResponse.class,
 *                    MetricCollectingClient.newDecorator(
 *                            MetricKeyFunction.defaultWithoutLabels("myClient)))
 *         .build(MyService.Iface.class);
 * }
 * </pre>
 *
 * <p>It is generally recommended not to use a class or package name as a metric name, because otherwise
 * seemingly harmless refactoring such as rename may break metric collection.
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public final class MetricCollectingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    /**
     * Returns a {@link Client} decorator that tracks request stats using {@link Metrics}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, MetricCollectingClient<I, O>> newDecorator(MetricKeyFunction metricKeyFunction) {
        requireNonNull(metricKeyFunction, "metricKeyFunction");
        return delegate -> new MetricCollectingClient<>(delegate, metricKeyFunction);
    }

    private final MetricKeyFunction metricKeyFunction;

    @SuppressWarnings("unchecked")
    MetricCollectingClient(Client<I, O> delegate, MetricKeyFunction metricKeyFunction) {
        super(delegate);
        this.metricKeyFunction = metricKeyFunction;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        MetricCollectionSupport.setup(ctx, metricKeyFunction);
        return delegate().execute(ctx, req);
    }
}
