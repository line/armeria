/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.common.loadbalancer.Weighted;

import io.envoyproxy.envoy.config.core.v3.Locality;

/**
 * A weighted locality that implements the {@link Weighted} interface.
 */
public final class WeightedLocality implements Weighted {

    private final Locality locality;
    private final int weight;

    WeightedLocality(Locality locality, int weight) {
        this.locality = locality;
        this.weight = weight;
    }

    Locality locality() {
        return locality;
    }

    @Override
    public int weight() {
        return weight;
    }
}
