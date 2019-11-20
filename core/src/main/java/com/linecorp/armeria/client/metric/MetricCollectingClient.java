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
package com.linecorp.armeria.client.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decorates an {@link HttpClient} to collect metrics into {@link MeterRegistry}.
 *
 * <p>Example:
 * <pre>{@code
 * WebClient client = WebClient
 *         .builder(uri)
 *         .decorator(MetricCollectingClient.newDecorator(MeterIdPrefixFunction.ofDefault("myClient")))
 *         .build();
 * }
 * </pre>
 *
 * <p>It is generally recommended not to use a class or package name as a metric name, because otherwise
 * seemingly harmless refactoring such as rename may break metric collection.
 */
public final class MetricCollectingClient extends AbstractMetricCollectingClient<HttpRequest, HttpResponse>
        implements HttpClient {

    /**
     * Returns an {@link HttpClient} decorator that tracks request stats using {@link MeterRegistry}.
     */
    public static Function<? super HttpClient, MetricCollectingClient> newDecorator(
            MeterIdPrefixFunction meterIdPrefixFunction) {
        requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        return delegate -> new MetricCollectingClient(delegate, meterIdPrefixFunction);
    }

    MetricCollectingClient(HttpClient delegate, MeterIdPrefixFunction meterIdPrefixFunction) {
        super(delegate, meterIdPrefixFunction);
    }
}
