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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.priority;

import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.xds.ClusterSnapshot;

import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;

final class PriorityStateManager {

    private final SortedMap<Integer, PriorityState.PriorityStateBuilder> priorityStateMap =
            new Int2ReferenceAVLTreeMap<>();
    private final ClusterSnapshot clusterSnapshot;
    private final List<Endpoint> origEndpoints;

    PriorityStateManager(ClusterSnapshot clusterSnapshot, List<Endpoint> origEndpoints) {
        this.clusterSnapshot = clusterSnapshot;
        this.origEndpoints = origEndpoints;
        for (Endpoint endpoint : origEndpoints) {
            registerEndpoint(endpoint);
        }
    }

    private void registerEndpoint(Endpoint endpoint) {
        final int priority = priority(endpoint);
        PriorityState.PriorityStateBuilder builder = priorityStateMap.get(priority);
        if (builder == null) {
            builder = priorityStateMap.computeIfAbsent(
                    priority(endpoint),
                    ignored -> new PriorityState.PriorityStateBuilder(clusterSnapshot));
        }
        builder.addEndpoint(endpoint);
    }

    PrioritySet build() {
        final PrioritySet.PrioritySetBuilder prioritySetBuilder =
                new PrioritySet.PrioritySetBuilder(clusterSnapshot, origEndpoints);
        for (Entry<Integer, PriorityState.PriorityStateBuilder> entry: priorityStateMap.entrySet()) {
            final Integer priority = entry.getKey();
            final PriorityState priorityState = entry.getValue().build();
            prioritySetBuilder.createHostSet(priority, priorityState.param());
        }
        return prioritySetBuilder.build();
    }
}
