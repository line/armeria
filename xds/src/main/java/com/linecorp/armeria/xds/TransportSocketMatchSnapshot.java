/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.TransportSocketMatch;

/**
 * A snapshot of a {@link TransportSocketMatch} resource with its associated
 * {@link TransportSocketSnapshot}. This allows conditional transport socket selection
 * based on endpoint metadata matching.
 */
public final class TransportSocketMatchSnapshot implements Snapshot<TransportSocketMatch> {

    private final TransportSocketMatch transportSocketMatch;
    private final TransportSocketSnapshot transportSocketSnapshot;

    TransportSocketMatchSnapshot(TransportSocketMatch transportSocketMatch,
                                 TransportSocketSnapshot transportSocketSnapshot) {
        this.transportSocketMatch = transportSocketMatch;
        this.transportSocketSnapshot = transportSocketSnapshot;
    }

    @Override
    public TransportSocketMatch xdsResource() {
        return transportSocketMatch;
    }

    /**
     * Returns the {@link TransportSocketSnapshot} to use when this match condition is satisfied.
     */
    public TransportSocketSnapshot transportSocket() {
        return transportSocketSnapshot;
    }
}
