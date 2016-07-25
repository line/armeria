/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.metrics;

import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.common.metrics.DropwizardMetricConsumer;
import com.linecorp.armeria.common.metrics.MetricConsumer;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;

/**
 * A decorator {@link Service} that collects metrics for every requests
 * Currently only HTTP-based session protocols are supported.
 */
public class MetricCollectingService extends DecoratingService {

    /**
     * A {@link Service} decorator that tracks request stats using the Dropwizard metrics library.
     * To use, simply prepare a {@link MetricRegistry} and add this decorator to a service specification.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNamePrefix the prefix of the names of the metrics created by the returned decorator.
     *
     * <p>Example:
     * <pre>{@code
     * MetricRegistry metricRegistry = new MetricRegistry();
     * serverBuilder.serviceAt(
     *         "/service",
     *         ThriftService.of(handler).decorate(
     *                 MetricCollectingService.newDropwizardDecorator(
     *                         metricRegistry, MetricRegister.name("services", "myService"))));
     * }
     * </pre>
     * <p>It is generally recommended to define your own name for the service instead of using something like
     * the Java class to make sure otherwise safe changes like renames don't break metrics.
     */
    public static Function<Service, Service> newDropwizardDecorator(MetricRegistry metricRegistry,
                                                                    String metricNamePrefix) {

        return service -> new MetricCollectingService(
                service,
                new DropwizardMetricConsumer(metricRegistry, metricNamePrefix));
    }

    public MetricCollectingService(Service service, MetricConsumer consumer) {
        super(service, x -> new MetricCollectingServiceCodec(x, consumer), Function.identity());
    }
}
