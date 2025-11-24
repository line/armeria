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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;

/**
 * Defines the IDs of internal {@code HttpService}s that should not be exposed to the external network.
 */
@UnstableApi
public enum InternalServiceId {
    /**
     * The ID of {@link DocService}.
     */
    DOCS,
    /**
     * The ID of {@link HttpService}.
     */
    HEALTH,
    /**
     * The ID of {@link PrometheusExpositionService}.
     */
    METRICS,
    /**
     * The ID to bind {@code WebOperationService} into internal service.
     */
    ACTUATOR,
    /**
     * The ID that represents all internal {@link HttpService}s.
     */
    ALL;

    /**
     * Returns the default service IDs that need to secure from the external network.
     */
    public static List<InternalServiceId> defaultServiceIds() {
        return ImmutableList.of(DOCS, HEALTH, METRICS, ACTUATOR);
    }
}
