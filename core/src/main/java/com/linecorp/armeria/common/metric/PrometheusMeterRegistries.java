/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;

/**
 * Provides the convenient factory methods for {@link PrometheusMeterRegistry} with more sensible defaults for
 * {@link NamingConvention}.
 */
public final class PrometheusMeterRegistries {

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with a new {@link CollectorRegistry}.
     */
    public static PrometheusMeterRegistry newRegistry() {
        return newRegistry(new CollectorRegistry());
    }

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with the specified
     * {@link CollectorRegistry}.
     */
    public static PrometheusMeterRegistry newRegistry(CollectorRegistry registry) {
        return newRegistry(registry, Clock.SYSTEM);
    }

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with the specified
     * {@link CollectorRegistry} and {@link Clock}.
     */
    public static PrometheusMeterRegistry newRegistry(CollectorRegistry registry, Clock clock) {
        final PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, requireNonNull(registry, "registry"), requireNonNull(clock, "clock"));
        meterRegistry.config().namingConvention(MoreNamingConventions.prometheus());
        return meterRegistry;
    }

    private PrometheusMeterRegistries() {}
}
