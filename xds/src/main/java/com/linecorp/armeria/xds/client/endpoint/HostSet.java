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

import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.endpoint.WeightedRandomDistributionSelector;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class HostSet {

    private final boolean weightedPriorityHealth;
    private final int overProvisioningFactor;

    private final WeightedRandomDistributionSelector<LocalityEntry> healthyLocalitySelector;
    private final WeightedRandomDistributionSelector<LocalityEntry> degradedLocalitySelector;

    private final EndpointGroup hostsEndpointGroup;
    private final EndpointGroup healthyHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality;
    private final EndpointGroup degradedHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality;

    HostSet(UpdateHostsParam params, ClusterLoadAssignment clusterLoadAssignment) {
        weightedPriorityHealth = EndpointUtil.weightedPriorityHealth(clusterLoadAssignment);
        overProvisioningFactor = EndpointUtil.overProvisionFactor(clusterLoadAssignment);

        healthyLocalitySelector = rebuildLocalityScheduler(
                params.healthyHostsPerLocality(), params.hostsPerLocality(),
                params.localityWeightsMap(), overProvisioningFactor);
        degradedLocalitySelector = rebuildLocalityScheduler(
                params.degradedHostsPerLocality(), params.hostsPerLocality(),
                params.localityWeightsMap(), overProvisioningFactor);

        hostsEndpointGroup = params.hosts();
        healthyHostsEndpointGroup = params.healthyHosts();
        degradedHostsEndpointGroup = params.degradedHosts();
        healthyEndpointGroupPerLocality = params.healthyHostsPerLocality();
        degradedEndpointGroupPerLocality = params.degradedHostsPerLocality();
    }

    List<Endpoint> hosts() {
        return hostsEndpointGroup.endpoints();
    }

    EndpointGroup hostsEndpointGroup() {
        return hostsEndpointGroup;
    }

    List<Endpoint> healthyHosts() {
        return healthyHostsEndpointGroup.endpoints();
    }

    EndpointGroup healthyHostsEndpointGroup() {
        return healthyHostsEndpointGroup;
    }

    Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality() {
        return healthyEndpointGroupPerLocality;
    }

    List<Endpoint> degradedHosts() {
        return degradedHostsEndpointGroup.endpoints();
    }

    EndpointGroup degradedHostsEndpointGroup() {
        return degradedHostsEndpointGroup;
    }

    Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality() {
        return degradedEndpointGroupPerLocality;
    }

    boolean weightedPriorityHealth() {
        return weightedPriorityHealth;
    }

    int overProvisioningFactor() {
        return overProvisioningFactor;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("hostsEndpointGroup", hostsEndpointGroup)
                          .add("healthyHostsEndpointGroup", healthyHostsEndpointGroup)
                          .add("healthyEndpointGroupPerLocality", healthyEndpointGroupPerLocality)
                          .add("degradedHostsEndpointGroup", degradedHostsEndpointGroup)
                          .add("degradedEndpointGroupPerLocality", degradedEndpointGroupPerLocality)
                          .add("weightedPriorityHealth", weightedPriorityHealth)
                          .add("overProvisioningFactor", overProvisioningFactor)
                          .toString();
    }

    private static WeightedRandomDistributionSelector<LocalityEntry> rebuildLocalityScheduler(
            Map<Locality, EndpointGroup> eligibleHostsPerLocality,
            Map<Locality, EndpointGroup> allHostsPerLocality,
            Map<Locality, Integer> localityWeightsMap,
            int overProvisioningFactor) {
        final ImmutableList.Builder<LocalityEntry> localityWeightsBuilder = ImmutableList.builder();
        for (Locality locality : allHostsPerLocality.keySet()) {
            final double effectiveWeight =
                    effectiveLocalityWeight(locality, eligibleHostsPerLocality, allHostsPerLocality,
                                            localityWeightsMap, overProvisioningFactor);
            if (effectiveWeight > 0) {
                localityWeightsBuilder.add(new LocalityEntry(locality, effectiveWeight));
            }
        }
        return new WeightedRandomDistributionSelector<>(localityWeightsBuilder.build());
    }

    static double effectiveLocalityWeight(Locality locality,
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

    @Nullable
    Locality chooseDegradedLocality() {
        final LocalityEntry localityEntry = degradedLocalitySelector.select();
        if (localityEntry == null) {
            return null;
        }
        return localityEntry.locality;
    }

    @Nullable
    Locality chooseHealthyLocality() {
        final LocalityEntry localityEntry = healthyLocalitySelector.select();
        if (localityEntry == null) {
            return null;
        }
        return localityEntry.locality;
    }

    static class LocalityEntry extends WeightedRandomDistributionSelector.AbstractEntry {

        private final Locality locality;
        private final int weight;

        LocalityEntry(Locality locality, double weight) {
            this.locality = locality;
            this.weight = Ints.saturatedCast(Math.round(weight));
        }

        @Override
        public int weight() {
            return weight;
        }
    }
}
