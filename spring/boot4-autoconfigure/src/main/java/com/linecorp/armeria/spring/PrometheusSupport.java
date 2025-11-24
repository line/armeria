/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.spring;

import java.util.Optional;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

final class PrometheusSupport {

    @Nullable
    static PrometheusExpositionService newExpositionService(MeterRegistry meterRegistry) {
        for (;;) {
            if (meterRegistry instanceof PrometheusMeterRegistry) {
                final PrometheusRegistry prometheusRegistry =
                        ((PrometheusMeterRegistry) meterRegistry).getPrometheusRegistry();
                return PrometheusExpositionService.of(prometheusRegistry);
            }

            if (meterRegistry instanceof CompositeMeterRegistry) {
                final Set<MeterRegistry> childRegistries =
                        ((CompositeMeterRegistry) meterRegistry).getRegistries();

                final Optional<PrometheusMeterRegistry> opt =
                        find(PrometheusMeterRegistry.class, childRegistries);
                if (opt.isPresent()) {
                    meterRegistry = opt.get();
                    continue;
                }

                return null;
            }

            return null;
        }
    }

    static <T> Optional<T> find(Class<T> type, Set<MeterRegistry> childRegistries) {
        return childRegistries.stream()
                              .filter(type::isInstance)
                              .map(type::cast)
                              .findAny();
    }

    private PrometheusSupport() {}
}
