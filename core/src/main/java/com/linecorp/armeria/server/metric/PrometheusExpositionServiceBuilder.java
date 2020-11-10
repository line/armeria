/*
 * Copyright 2020 LINE Corporation
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

import java.util.EnumSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.server.OptOutFeature;
import com.linecorp.armeria.server.TransientServiceBuilder;

import io.prometheus.client.CollectorRegistry;

/**
 * Builds a {@link PrometheusExpositionService}.
 */
public final class PrometheusExpositionServiceBuilder implements TransientServiceBuilder {

    private final CollectorRegistry collectorRegistry;

    @Nullable
    private Set<OptOutFeature> optOutFeatures;

    PrometheusExpositionServiceBuilder(CollectorRegistry collectorRegistry) {
        this.collectorRegistry = requireNonNull(collectorRegistry, "collectorRegistry");
    }

    @Override
    public PrometheusExpositionServiceBuilder optOutFeatures(OptOutFeature... optOutFeatures) {
        return optOutFeatures(ImmutableSet.copyOf(requireNonNull(optOutFeatures, "optOutFeatures")));
    }

    @Override
    public PrometheusExpositionServiceBuilder optOutFeatures(Iterable<OptOutFeature> optOutFeatures) {
        requireNonNull(optOutFeatures, "optOutFeatures");
        if (this.optOutFeatures == null) {
            this.optOutFeatures = EnumSet.noneOf(OptOutFeature.class);
        }
        this.optOutFeatures.addAll(ImmutableSet.copyOf(optOutFeatures));
        return this;
    }

    /**
     * Returns a newly-created {@link PrometheusExpositionService} based on the properties of this builder.
     */
    public PrometheusExpositionService build() {
        final Set<OptOutFeature> optOutFeatures;
        if (this.optOutFeatures == null) {
            optOutFeatures = Flags.optOutFeatures();
        } else {
            optOutFeatures = ImmutableSet.copyOf(this.optOutFeatures);
        }

        return new PrometheusExpositionService(collectorRegistry, optOutFeatures);
    }
}
