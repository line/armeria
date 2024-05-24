/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server.metric;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.internal.server.TransientServiceOptionsBuilder;
import com.linecorp.armeria.server.TransientServiceBuilder;
import com.linecorp.armeria.server.TransientServiceOption;

import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Builds a {@link PrometheusVersion1ExpositionService}.
 */
public final class PrometheusVersion1ExpositionServiceBuilder implements TransientServiceBuilder {

    private final PrometheusRegistry prometheusRegistry;

    private final TransientServiceOptionsBuilder
            transientServiceOptionsBuilder = new TransientServiceOptionsBuilder();

    PrometheusVersion1ExpositionServiceBuilder(PrometheusRegistry prometheusRegistry) {
        this.prometheusRegistry = requireNonNull(prometheusRegistry, "prometheusRegistry");
    }

    @Override
    public PrometheusVersion1ExpositionServiceBuilder transientServiceOptions(
            TransientServiceOption... transientServiceOptions) {
        transientServiceOptionsBuilder.transientServiceOptions(transientServiceOptions);
        return this;
    }

    @Override
    public PrometheusVersion1ExpositionServiceBuilder transientServiceOptions(
            Iterable<TransientServiceOption> transientServiceOptions) {
        transientServiceOptionsBuilder.transientServiceOptions(transientServiceOptions);
        return this;
    }

    /**
     * Returns a newly-created {@link PrometheusVersion1ExpositionService} based on the properties
     * of this builder.
     */
    public PrometheusVersion1ExpositionService build() {
        return new PrometheusVersion1ExpositionService(prometheusRegistry,
                                                       transientServiceOptionsBuilder.build());
    }
}
