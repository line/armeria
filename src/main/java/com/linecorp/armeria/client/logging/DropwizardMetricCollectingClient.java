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

package com.linecorp.armeria.client.logging;

import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.logging.DropwizardMetricConsumer;

/**
 * Decorates a {@link Client} to collect metrics into Dropwizard {@link MetricRegistry}.
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public final class DropwizardMetricCollectingClient<I extends Request, O extends Response>
        extends LogCollectingClient<I, O> {

    /**
     * A {@link Client} decorator that tracks request stats using the Dropwizard metrics library.
     * To use, simply prepare a {@link MetricRegistry} and add this decorator to a client.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNamePrefix the prefix of the names of the metrics created by the returned decorator.
     *
     * <p>Example:
     * <pre>{@code
     * MetricRegistry metricRegistry = new MetricRegistry();
     * MyService.Iface client = new ClientBuilder(uri)
     *         .decorate(MetricCollectingClient.newDropwizardDecorator(
     *                 metricRegistry, MetricRegistry.name("clients", "myService")))
     *         .build(MyService.Iface.class);
     * }
     * </pre>
     * <p>It is generally recommended to define your own name for the service instead of using something like
     * the Java class to make sure otherwise safe changes like renames don't break metrics.
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, DropwizardMetricCollectingClient<I, O>> newDecorator(
            MetricRegistry metricRegistry, String metricNamePrefix) {

        return client -> new DropwizardMetricCollectingClient<>(
                client, metricRegistry, metricNamePrefix);
    }

    DropwizardMetricCollectingClient(Client<? super I, ? extends O> delegate,
                                     MetricRegistry metricRegistry, String metricNamePrefix) {
        super(delegate, new DropwizardMetricConsumer(metricRegistry, metricNamePrefix));
    }
}
