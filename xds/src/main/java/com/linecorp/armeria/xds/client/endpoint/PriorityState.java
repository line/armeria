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

import static com.linecorp.armeria.xds.client.endpoint.EndpointGroupUtil.endpointsByLocality;
import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.locality;
import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.selectionStrategy;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Locality;

final class PriorityState {
    private final UpdateHostsParam param;

    PriorityState(List<Endpoint> hosts, Map<Locality, Integer> localityWeightsMap,
                  Cluster cluster) {
        final Map<Locality, List<Endpoint>> endpointsPerLocality = endpointsByLocality(hosts);
        param = new UpdateHostsParam(hosts, endpointsPerLocality, localityWeightsMap,
                                     selectionStrategy(cluster));
    }

    UpdateHostsParam param() {
        return param;
    }

    static final class PriorityStateBuilder {

        private final ImmutableList.Builder<Endpoint> hostsBuilder = ImmutableList.builder();
        private final ImmutableMap.Builder<Locality, Integer> localityWeightsBuilder =
                ImmutableMap.builder();
        private final Cluster cluster;

        PriorityStateBuilder(Cluster cluster) {
            this.cluster = cluster;
        }

        void addEndpoint(Endpoint endpoint) {
            hostsBuilder.add(endpoint);
            if (locality(endpoint) != Locality.getDefaultInstance() &&
                EndpointUtil.hasLoadBalancingWeight(endpoint)) {
                localityWeightsBuilder.put(locality(endpoint), endpoint.weight());
            }
        }

        PriorityState build() {
            return new PriorityState(hostsBuilder.build(), localityWeightsBuilder.buildKeepingLast(),
                                     cluster);
        }
    }
}
