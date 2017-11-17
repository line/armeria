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

package com.linecorp.armeria.server;

import java.io.OutputStream;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Finds a mapping that matches a given {@link PathMappingContext}.
 */
public interface Router<V> {

    /**
     * Finds the value of mapping that matches the specified {@link PathMappingContext}.
     *
     * @return a {@link PathMapped} that wraps the matching value if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    PathMapped<V> find(PathMappingContext mappingCtx);

    /**
     * Registers the stats of this {@link Router} to the specified {@link MeterRegistry}.
     *
     * @return whether the stats of this {@link Router} has been registered
     */
    default boolean registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
        return false;
    }

    /**
     * Dumps the content of this {@link Router}.
     */
    void dump(OutputStream output);
}
