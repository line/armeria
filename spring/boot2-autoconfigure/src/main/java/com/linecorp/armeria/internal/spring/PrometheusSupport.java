/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import java.util.Optional;
import java.util.Set;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.spring.ArmeriaSettings;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;

final class PrometheusSupport {

    static boolean addExposition(ArmeriaSettings settings, ServerBuilder server,
                                 MeterRegistry meterRegistry) {

        final String metricsPath = settings.getMetricsPath();
        assert metricsPath != null;

        for (;;) {
            if (meterRegistry instanceof PrometheusMeterRegistry) {
                final CollectorRegistry prometheusRegistry =
                        ((PrometheusMeterRegistry) meterRegistry).getPrometheusRegistry();
                server.service(metricsPath, new PrometheusExpositionService(prometheusRegistry));
                return true;
            }

            if (meterRegistry instanceof CompositeMeterRegistry) {
                final Set<MeterRegistry> childRegistries =
                        ((CompositeMeterRegistry) meterRegistry).getRegistries();
                final Optional<PrometheusMeterRegistry> opt =
                        childRegistries.stream()
                                       .filter(PrometheusMeterRegistry.class::isInstance)
                                       .map(PrometheusMeterRegistry.class::cast)
                                       .findAny();

                if (!opt.isPresent()) {
                    return false;
                }

                meterRegistry = opt.get();
                continue;
            }

            return false;
        }
    }

    private PrometheusSupport() {}
}
