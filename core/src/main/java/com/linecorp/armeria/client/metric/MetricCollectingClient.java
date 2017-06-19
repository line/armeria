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
import com.linecorp.armeria.common.metric.MeterIdFunction;
import com.linecorp.armeria.internal.metric.RequestMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decorates a {@link Client} to collect metrics into {@link MeterRegistry}.
 *
 * <p>Example:
 * <pre>{@code
 * MyService.Iface client = new ClientBuilder(uri)
 *         .decorator(HttpRequest.class, HttpResponse.class,
 *                    MetricCollectingClient.newDecorator(
 *                            MeterIdFunction.ofDefault("myClient)))
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
     * Returns a {@link Client} decorator that tracks request stats using {@link MeterRegistry}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, MetricCollectingClient<I, O>> newDecorator(MeterIdFunction meterIdFunction) {
        requireNonNull(meterIdFunction, "meterIdFunction");
        return delegate -> new MetricCollectingClient<>(delegate, meterIdFunction);
    }

    private final MeterIdFunction meterIdFunction;

    @SuppressWarnings("unchecked")
    MetricCollectingClient(Client<I, O> delegate, MeterIdFunction meterIdFunction) {
        super(delegate);
        this.meterIdFunction = requireNonNull(meterIdFunction, "meterIdFunction");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        RequestMetricSupport.setup(ctx, meterIdFunction);
        return delegate().execute(ctx, req);
    }
}
