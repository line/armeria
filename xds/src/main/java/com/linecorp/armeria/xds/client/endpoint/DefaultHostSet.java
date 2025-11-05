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

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;
import com.linecorp.armeria.common.loadbalancer.SimpleLoadBalancer;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class DefaultHostSet implements HostSet {

    private final boolean weightedPriorityHealth;
    private final int overProvisioningFactor;

    private final SimpleLoadBalancer<WeightedLocality> healthyLocalitySelector;
    private final SimpleLoadBalancer<WeightedLocality> degradedLocalitySelector;

    private final EndpointGroup hostsEndpointGroup;
    private final Map<Locality, EndpointGroup> endpointGroupPerLocality;
    private final EndpointGroup healthyHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality;
    private final EndpointGroup degradedHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality;
    private final Map<Locality, Integer> localityWeights;

    DefaultHostSet(UpdateHostsParam params, ClusterSnapshot clusterSnapshot) {
        weightedPriorityHealth = EndpointUtil.weightedPriorityHealth(clusterSnapshot);
        overProvisioningFactor = EndpointUtil.overProvisionFactor(clusterSnapshot);

        healthyLocalitySelector = rebuildLocalityScheduler(
                params.healthyHostsPerLocality(), params.hostsPerLocality(),
                params.localityWeightsMap(), overProvisioningFactor);
        degradedLocalitySelector = rebuildLocalityScheduler(
                params.degradedHostsPerLocality(), params.hostsPerLocality(),
                params.localityWeightsMap(), overProvisioningFactor);

        hostsEndpointGroup = params.hosts();
        endpointGroupPerLocality = params.hostsPerLocality();
        healthyHostsEndpointGroup = params.healthyHosts();
        degradedHostsEndpointGroup = params.degradedHosts();
        healthyEndpointGroupPerLocality = params.healthyHostsPerLocality();
        degradedEndpointGroupPerLocality = params.degradedHostsPerLocality();
        localityWeights = params.localityWeightsMap();
    }

    @Override
    public EndpointGroup hostsEndpointGroup() {
        return hostsEndpointGroup;
    }

    @Override
    public Map<Locality, EndpointGroup> endpointGroupPerLocality() {
        return endpointGroupPerLocality;
    }

    @Override
    public EndpointGroup healthyHostsEndpointGroup() {
        return healthyHostsEndpointGroup;
    }

    @Override
    public Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality() {
        return healthyEndpointGroupPerLocality;
    }

    @Override
    public EndpointGroup degradedHostsEndpointGroup() {
        return degradedHostsEndpointGroup;
    }

    @Override
    public Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality() {
        return degradedEndpointGroupPerLocality;
    }

    @Override
    public Map<Locality, Integer> localityWeights() {
        return localityWeights;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("hostsEndpointGroup", hostsEndpointGroup)
                          .add("healthyHostsEndpointGroup", healthyHostsEndpointGroup)
                          .add("healthyEndpointGroupPerLocality", healthyEndpointGroupPerLocality)
                          .add("degradedHostsEndpointGroup", degradedHostsEndpointGroup)
                          .add("degradedEndpointGroupPerLocality", degradedEndpointGroupPerLocality)
                          .add("localityWeights", localityWeights)
                          .add("weightedPriorityHealth", weightedPriorityHealth)
                          .add("overProvisioningFactor", overProvisioningFactor)
                          .toString();
    }

    private static SimpleLoadBalancer<WeightedLocality> rebuildLocalityScheduler(
            Map<Locality, EndpointGroup> eligibleHostsPerLocality,
            Map<Locality, EndpointGroup> allHostsPerLocality,
            Map<Locality, Integer> localityWeightsMap,
            int overProvisioningFactor) {
        final ImmutableList.Builder<WeightedLocality> localityWeightsBuilder = ImmutableList.builder();
        for (Locality locality : allHostsPerLocality.keySet()) {
            final double effectiveWeight =
                    effectiveLocalityWeight(locality, eligibleHostsPerLocality, allHostsPerLocality,
                                            localityWeightsMap, overProvisioningFactor);
            if (effectiveWeight > 0) {
                final int weight = Ints.saturatedCast(Math.round(effectiveWeight));
                localityWeightsBuilder.add(new WeightedLocality(locality, weight));
            }
        }
        return LoadBalancer.ofWeightedRandom(localityWeightsBuilder.build());
    }

    private static double effectiveLocalityWeight(Locality locality,
                                                  Map<Locality, EndpointGroup> eligibleHostsPerLocality,
                                                  Map<Locality, EndpointGroup> allHostsPerLocality,
                                                  Map<Locality, Integer> localityWeightsMap,
                                                  int overProvisioningFactor) {
        final EndpointGroup localityEligibleHosts =
                eligibleHostsPerLocality.getOrDefault(locality, EndpointGroup.of());
        final int hostCount = allHostsPerLocality.getOrDefault(locality, EndpointGroup.of()).endpoints().size();
        if (hostCount == 0) {
            return 0;
        }
        // We compute the availability of a locality via:
        // (overProvisioningFactor) * (# healthy/degraded of hosts) / (# total hosts)
        // https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/locality_weight.html
        final double localityAvailabilityRatio = (double) localityEligibleHosts.endpoints().size() / hostCount;
        final int weight = localityWeightsMap.getOrDefault(locality, 0);
        final double effectiveLocalityAvailabilityRatio =
                Math.min(1.0, (overProvisioningFactor / 100.0) * localityAvailabilityRatio);
        return weight * effectiveLocalityAvailabilityRatio;
    }

    @Override
    public SimpleLoadBalancer<WeightedLocality> degradedLocalitySelector() {
        return degradedLocalitySelector;
    }

    @Override
    public SimpleLoadBalancer<WeightedLocality> healthyLocalitySelector() {
        return healthyLocalitySelector;
    }
}
