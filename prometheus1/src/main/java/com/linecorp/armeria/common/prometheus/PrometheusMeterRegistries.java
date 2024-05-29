/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.prometheus;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Provides the convenient factory methods for {@link PrometheusMeterRegistry}.
 */
@UnstableApi
public final class PrometheusMeterRegistries {

    private static final PrometheusMeterRegistry defaultRegistry =
            newRegistry(PrometheusRegistry.defaultRegistry);

    /**
     * Returns the default {@link PrometheusMeterRegistry} that uses {@link PrometheusRegistry#defaultRegistry}.
     */
    public static PrometheusMeterRegistry defaultRegistry() {
        return defaultRegistry;
    }

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with a new {@link PrometheusRegistry}.
     */
    public static PrometheusMeterRegistry newRegistry() {
        return newRegistry(new PrometheusRegistry());
    }

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with the specified
     * {@link PrometheusRegistry}.
     */
    public static PrometheusMeterRegistry newRegistry(PrometheusRegistry registry) {
        return newRegistry(registry, Clock.SYSTEM);
    }

    /**
     * Returns a newly-created {@link PrometheusMeterRegistry} instance with the specified
     * {@link PrometheusRegistry} and {@link Clock}.
     */
    public static PrometheusMeterRegistry newRegistry(PrometheusRegistry registry, Clock clock) {
        return new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, requireNonNull(registry, "registry"), requireNonNull(clock, "clock"));
    }

    private PrometheusMeterRegistries() {}
}
