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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.AbstractMessage;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.XdsRandom.RandomHint;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class LocalityRoutingStateFactory {

    private static final long MOD = 10000;

    private final Locality localLocality;

    LocalityRoutingStateFactory(Locality localLocality) {
        this.localLocality = localLocality;
    }

    LocalityRoutingState create(PrioritySet upstreamPrioritySet, PrioritySet localPrioritySet) {
        // Only priority 0 is supported
        final HostSet upstreamHostSet = upstreamPrioritySet.hostSets().get(0);
        final HostSet localHostSet = localPrioritySet.hostSets().get(0);
        if (upstreamHostSet == null || localHostSet == null ||
            earlyExitNonLocalityRouting(upstreamHostSet, localHostSet, localLocality,
                                        upstreamPrioritySet.minClusterSize())) {
            return new LocalityRoutingState(State.NO_LOCALITY_ROUTING, localHostSet, localLocality);
        }

        final Map<Locality, EndpointGroup> upstreamHealthyPerLocality =
                upstreamHostSet.healthyEndpointGroupPerLocality();
        final Map<Locality, EndpointGroup> localHealthyPerLocality =
                localHostSet.healthyEndpointGroupPerLocality();

        final Map<Locality, LocalityPercentages> localityPercentages = calculateLocalityPercentages(
                upstreamHealthyPerLocality, localHealthyPerLocality);

        final LocalityPercentages localLocalityPercentages = localityPercentages.get(localLocality);
        if (localLocalityPercentages != null && localLocalityPercentages.canRouteToUpstream()) {
            return new LocalityRoutingState(State.LOCALITY_DIRECT, localHostSet, localLocality);
        }

        final long localPercentToRoute = localLocalityPercentages != null ?
                                         localLocalityPercentages.localPercentToRoute() : 0;
        final List<Locality> upstreamLocalities =
                upstreamHealthyPerLocality.keySet().stream()
                                          .filter(locality -> !locality.equals(localLocality))
                                          // we assume there aren't many localities anyway, so
                                          // we just simply sort by the string-ed value
                                          .sorted(Comparator.comparing(AbstractMessage::toString))
                                          .collect(Collectors.toList());
        final ImmutableList.Builder<ResidualCapacity> residualCapacity = ImmutableList.builder();
        // to guarantee that residualCapacity has at least one element
        residualCapacity.add(new ResidualCapacity(localLocality, 0));
        long lastResidualCapacity = 0;
        for (Locality locality : upstreamLocalities) {
            final LocalityPercentages percentages = localityPercentages.get(locality);
            assert percentages != null;
            long newResidualCapacity = lastResidualCapacity;
            if (percentages.canRouteToUpstream()) {
                newResidualCapacity = lastResidualCapacity + percentages.offset();
            }
            residualCapacity.add(new ResidualCapacity(locality, newResidualCapacity));
            lastResidualCapacity = newResidualCapacity;
        }
        return new LocalityRoutingState(localPercentToRoute, residualCapacity.build(),
                                        localHostSet, localLocality);
    }

    static final class ResidualCapacity {
        private final Locality locality;
        private final long capacity;

        private ResidualCapacity(Locality locality, long capacity) {
            this.locality = locality;
            this.capacity = capacity;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("locality", locality)
                              .add("capacity", capacity)
                              .toString();
        }
    }

    enum State {
        NO_LOCALITY_ROUTING,
        LOCALITY_DIRECT,
        LOCALITY_RESIDUAL,
    }

    static final class LocalityRoutingState {

        private final State state;
        private final long localPercentageToRoute;
        private final List<ResidualCapacity> residualCapacities;
        @Nullable
        private final HostSet localHostSet;
        private final Locality localLocality;

        private LocalityRoutingState(State state, @Nullable HostSet localHostSet, Locality localLocality) {
            assert state == State.NO_LOCALITY_ROUTING || state == State.LOCALITY_DIRECT;
            this.state = state;
            localPercentageToRoute = 0;
            residualCapacities = ImmutableList.of();
            this.localHostSet = localHostSet;
            this.localLocality = localLocality;
        }

        private LocalityRoutingState(long localPercentageToRoute, List<ResidualCapacity> residualCapacities,
                                     HostSet localHostSet, Locality localLocality) {
            assert !residualCapacities.isEmpty();
            state = State.LOCALITY_RESIDUAL;
            this.localPercentageToRoute = localPercentageToRoute;
            this.residualCapacities = residualCapacities;
            this.localHostSet = localHostSet;
            this.localLocality = localLocality;
        }

        State state() {
            return state;
        }

        @Nullable
        HostSet localHostSet() {
            return localHostSet;
        }

        Locality localLocality() {
            return localLocality;
        }

        Locality tryChooseLocalLocalityHosts(HostSet hostSet, XdsRandom random) {
            assert state != State.NO_LOCALITY_ROUTING;
            if (state == State.LOCALITY_DIRECT) {
                return localLocality();
            }

            assert !residualCapacities.isEmpty();

            if (random.nextLong(MOD, RandomHint.LOCAL_PERCENTAGE) < localPercentageToRoute) {
                return localLocality();
            }
            final long lastCapacity = residualCapacities.get(residualCapacities.size() - 1).capacity;
            if (lastCapacity == 0) {
                // This is *extremely* unlikely but possible due to rounding errors when calculating
                // locality percentages. In this case just select random locality.
                final int idx = random.nextInt(residualCapacities.size(), RandomHint.ALL_RESIDUAL_ZERO);
                return residualCapacities.get(idx).locality;
            }
            final long threshold = random.nextLong(lastCapacity, RandomHint.LOCAL_THRESHOLD);
            // This potentially can be optimized to be O(log(N)) where N is the number of localities.
            // Linear scan should be faster for smaller N, in most of the scenarios N will be small.
            //
            // Bucket 1: [0, state.residual_capacity_[0] - 1]
            // Bucket 2: [state.residual_capacity_[0], state.residual_capacity_[1] - 1]
            // ...
            // Bucket N: [state.residual_capacity_[N-2], state.residual_capacity_[N-1] - 1]
            for (int i = 0; i < residualCapacities.size(); i++) {
                final ResidualCapacity residualCapacity = residualCapacities.get(i);
                if (threshold < residualCapacity.capacity) {
                    return residualCapacity.locality;
                }
            }
            throw new IllegalStateException(
                    "Unexpectedly couldn't choose a locality for hostSet: (" + hostSet +
                    ") and localLocality: (" + localLocality + ") in state (" + this + ')');
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("state", state)
                              .add("localPercentageToRoute", localPercentageToRoute)
                              .add("residualCapacities", residualCapacities)
                              .toString();
        }
    }

    private static Map<Locality, LocalityPercentages> calculateLocalityPercentages(
            Map<Locality, EndpointGroup> upstreamHealthyPerLocality,
            Map<Locality, EndpointGroup> localHealthyPerLocality) {
        int totalLocalHosts = 0;
        final ImmutableMap.Builder<Locality, Integer> localCountsBuilder = ImmutableMap.builder();
        for (Entry<Locality, EndpointGroup> entry: localHealthyPerLocality.entrySet()) {
            final EndpointGroup endpointGroup = entry.getValue();
            totalLocalHosts += endpointGroup.endpoints().size();
            if (!endpointGroup.endpoints().isEmpty()) {
                localCountsBuilder.put(entry.getKey(), endpointGroup.endpoints().size());
            }
        }
        final Map<Locality, Integer> localCounts = localCountsBuilder.build();
        final int totalUpstreamHosts =
                upstreamHealthyPerLocality.values().stream()
                                          .mapToInt(endpointGroup -> endpointGroup.endpoints().size()).sum();
        final ImmutableMap.Builder<Locality, LocalityPercentages> localityPercentagesBuilder =
                ImmutableMap.builder();
        for (Entry<Locality, EndpointGroup> entry: upstreamHealthyPerLocality.entrySet()) {
            final Locality locality = entry.getKey();
            final EndpointGroup upstreamEndpointGroup = entry.getValue();
            if (upstreamEndpointGroup.endpoints().isEmpty()) {
                localityPercentagesBuilder.put(locality, LocalityPercentages.NOOP);
                continue;
            }
            final int localHostsCount = localCounts.getOrDefault(locality, 0);
            final int upstreamHostsCount = upstreamEndpointGroup.endpoints().size();
            final long localPercentage = totalLocalHosts > 0 ? MOD * localHostsCount / totalLocalHosts : 0;
            final long upstreamPercentage = totalUpstreamHosts > 0 ?
                                            MOD * upstreamHostsCount / totalUpstreamHosts : 0;
            localityPercentagesBuilder.put(locality, new LocalityPercentages(localPercentage,
                                                                             upstreamPercentage));
        }
        return localityPercentagesBuilder.build();
    }

    private static boolean earlyExitNonLocalityRouting(HostSet upstreamHostSet, HostSet localHostSet,
                                                       Locality localLocality, long minClusterSize) {
        final Map<Locality, EndpointGroup> upstreamHealthyHostsPerLocality =
                upstreamHostSet.healthyEndpointGroupPerLocality();
        final Map<Locality, EndpointGroup> localHealthyHostsPerLocality =
                localHostSet.healthyEndpointGroupPerLocality();
        if (upstreamHealthyHostsPerLocality.size() < 2 || localHealthyHostsPerLocality.size() < 2) {
            return true;
        }

        if (localLocality == Locality.getDefaultInstance()) {
            return true;
        }
        final EndpointGroup localLocalityHealthyHosts = localHealthyHostsPerLocality.get(localLocality);
        if (localLocalityHealthyHosts == null || localLocalityHealthyHosts.endpoints().isEmpty()) {
            return true;
        }

        final int healthyUpstreamHostsSize = upstreamHostSet.healthyHostsEndpointGroup().endpoints().size();
        return healthyUpstreamHostsSize < minClusterSize;
    }

    private static final class LocalityPercentages {

        private static final LocalityPercentages NOOP = new LocalityPercentages(0, 0);

        private final long localPercentage;
        private final long upstreamPercentage;

        private LocalityPercentages(long localPercentage, long upstreamPercentage) {
            this.localPercentage = localPercentage;
            this.upstreamPercentage = upstreamPercentage;
        }

        boolean canRouteToUpstream() {
            return upstreamPercentage > 0 && upstreamPercentage >= localPercentage;
        }

        long offset() {
            return upstreamPercentage - localPercentage;
        }

        long localPercentToRoute() {
            if (localPercentage == 0) {
                return 0;
            }
            return upstreamPercentage * MOD / localPercentage;
        }
    }
}
