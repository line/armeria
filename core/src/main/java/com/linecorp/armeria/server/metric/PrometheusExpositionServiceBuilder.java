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

import static com.linecorp.armeria.internal.server.TransientServiceUtil.defaultTransientServiceActions;
import static java.util.Objects.requireNonNull;

import java.util.EnumMap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.server.TransientService.ActionType;

import io.prometheus.client.CollectorRegistry;

/**
 * Builds a {@link PrometheusExpositionService}.
 */
public final class PrometheusExpositionServiceBuilder {

    private final CollectorRegistry collectorRegistry;

    private final EnumMap<ActionType, Boolean> transientServiceActions;

    PrometheusExpositionServiceBuilder(CollectorRegistry collectorRegistry) {
        this.collectorRegistry = requireNonNull(collectorRegistry, "collectorRegistry");
        transientServiceActions = new EnumMap<>(ActionType.class);
        transientServiceActions.putAll(defaultTransientServiceActions());
    }

    /**
     * Sets whether the specified {@link ActionType} is enabled or not for the
     * {@link #build() PrometheusExpositionService}. All {@link ActionType}s are disabled by default.
     */
    public PrometheusExpositionServiceBuilder transientServiceAction(ActionType actionType, boolean enable) {
        transientServiceActions.put(requireNonNull(actionType, "actionType"), enable);
        return this;
    }

    /**
     * Returns a newly-created {@link PrometheusExpositionService} based on the properties of this builder.
     */
    public PrometheusExpositionService build() {
        return new PrometheusExpositionService(collectorRegistry,
                                               Maps.newEnumMap(ImmutableMap.copyOf(transientServiceActions)));
    }
}
