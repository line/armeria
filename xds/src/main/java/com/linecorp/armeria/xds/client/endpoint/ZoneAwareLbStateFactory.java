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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.math.IntMath;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.ZoneAwareLoadBalancer.DistributeLoadState;
import com.linecorp.armeria.xds.client.endpoint.ZoneAwareLoadBalancer.HostAvailability;
import com.linecorp.armeria.xds.client.endpoint.ZoneAwareLoadBalancer.PriorityAndAvailability;

final class ZoneAwareLbStateFactory {

    static ZoneAwareLbState newInstance(PrioritySet prioritySet) {
        final ImmutableMap.Builder<Integer, Integer> perPriorityHealthBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<Integer, Integer> perPriorityDegradedBuilder =
                ImmutableMap.builder();
        for (Integer priority: prioritySet.priorities()) {
            final HealthAndDegraded healthAndDegraded =
                    recalculatePerPriorityState(priority, prioritySet);
            perPriorityHealthBuilder.put(priority, healthAndDegraded.healthWeight);
            perPriorityDegradedBuilder.put(priority, healthAndDegraded.degradedWeight);
        }
        PerPriorityLoad perPriorityLoad = buildLoads(prioritySet,
                                                     perPriorityHealthBuilder.build(),
                                                     perPriorityDegradedBuilder.build());
        final PerPriorityPanic perPriorityPanic =
                recalculatePerPriorityPanic(prioritySet,
                                            perPriorityLoad.normalizedTotalAvailability());
        if (perPriorityPanic.totalPanic()) {
            perPriorityLoad = recalculateLoadInTotalPanic(prioritySet);
        }
        return new ZoneAwareLbState(prioritySet, perPriorityLoad, perPriorityPanic);
    }

    private static HealthAndDegraded recalculatePerPriorityState(
            int priority, PrioritySet prioritySet) {
        final HostSet hostSet = prioritySet.hostSets().get(priority);
        final int hostCount = hostSet.hosts().size();

        if (hostCount > 0) {
            long healthyWeight = 0;
            long degradedWeight = 0;
            long totalWeight = 0;
            if (hostSet.weightedPriorityHealth()) {
                for (Endpoint host : hostSet.healthyHosts()) {
                    healthyWeight += host.weight();
                }
                for (Endpoint host : hostSet.degradedHosts()) {
                    degradedWeight += host.weight();
                }
                for (Endpoint host : hostSet.hosts()) {
                    totalWeight += host.weight();
                }
            } else {
                healthyWeight = hostSet.healthyHosts().size();
                degradedWeight = hostSet.degradedHosts().size();
                totalWeight = hostCount;
            }
            final int health = (int) Math.min(100L, LongMath.saturatedMultiply(
                    hostSet.overProvisioningFactor(), healthyWeight) / totalWeight);
            final int degraded = (int) Math.min(100L, LongMath.saturatedMultiply(
                    hostSet.overProvisioningFactor(), degradedWeight) / totalWeight);
            return new HealthAndDegraded(health, degraded);
        }
        return new HealthAndDegraded(0, 0);
    }

    private static PerPriorityLoad buildLoads(PrioritySet prioritySet,
                                              Map<Integer, Integer> perPriorityHealth,
                                              Map<Integer, Integer> perPriorityDegraded) {
        final int normalizedTotalAvailability =
                normalizedTotalAvailability(perPriorityHealth, perPriorityDegraded);
        if (normalizedTotalAvailability == 0) {
            return PerPriorityLoad.INVALID;
        }

        final Map<Integer, Integer> healthyPriorityLoad = new HashMap<>();
        final Map<Integer, Integer> degradedPriorityLoad = new HashMap<>();
        final DistributeLoadState firstHealthyAndRemaining =
                distributeLoad(prioritySet.priorities(), healthyPriorityLoad, perPriorityHealth,
                               100, normalizedTotalAvailability);
        final DistributeLoadState firstDegradedAndRemaining =
                distributeLoad(prioritySet.priorities(), degradedPriorityLoad, perPriorityDegraded,
                               firstHealthyAndRemaining.totalLoad, normalizedTotalAvailability);
        final int remainingLoad = firstDegradedAndRemaining.totalLoad;
        if (remainingLoad > 0) {
            final int firstHealthy = firstHealthyAndRemaining.firstAvailablePriority;
            final int firstDegraded = firstDegradedAndRemaining.firstAvailablePriority;
            if (firstHealthy != -1) {
                healthyPriorityLoad.computeIfPresent(firstHealthy, (k, v) -> v + remainingLoad);
            } else {
                assert firstDegraded != -1;
                degradedPriorityLoad.computeIfPresent(firstDegraded, (k, v) -> v + remainingLoad);
            }
        }

        assert priorityLoadSum(healthyPriorityLoad, degradedPriorityLoad) == 100;
        return new PerPriorityLoad(healthyPriorityLoad, degradedPriorityLoad,
                                   normalizedTotalAvailability);
    }

    private static int normalizedTotalAvailability(Map<Integer, Integer> perPriorityHealth,
                                                   Map<Integer, Integer> perPriorityDegraded) {
        final int totalAvailability = Streams.concat(perPriorityHealth.values().stream(),
                                                     perPriorityDegraded.values().stream())
                                             .reduce(0, IntMath::saturatedAdd).intValue();
        return Math.min(totalAvailability, 100);
    }

    private static int priorityLoadSum(Map<Integer, Integer> healthyPriorityLoad,
                                       Map<Integer, Integer> degradedPriorityLoad) {
        return Streams.concat(healthyPriorityLoad.values().stream(),
                              degradedPriorityLoad.values().stream())
                      .reduce(0, IntMath::saturatedAdd).intValue();
    }

    private static DistributeLoadState distributeLoad(SortedSet<Integer> priorities,
                                                      Map<Integer, Integer> perPriorityLoad,
                                                      Map<Integer, Integer> perPriorityAvailability,
                                                      int totalLoad, int normalizedTotalAvailability) {
        int firstAvailablePriority = -1;
        for (Integer priority: priorities) {
            final long availability = perPriorityAvailability.getOrDefault(priority, 0);
            if (firstAvailablePriority < 0 && availability > 0) {
                firstAvailablePriority = priority;
            }
            final int load = (int) Math.min(totalLoad, availability * 100 / normalizedTotalAvailability);
            perPriorityLoad.put(priority, load);
            totalLoad -= load;
        }
        return new DistributeLoadState(totalLoad, firstAvailablePriority);
    }

    private static PerPriorityPanic recalculatePerPriorityPanic(PrioritySet prioritySet,
                                                                int normalizedTotalAvailability) {
        final int panicThreshold = prioritySet.panicThreshold();
        if (normalizedTotalAvailability == 0 && panicThreshold == 0) {
            // there are no hosts available and panic mode is disabled.
            // we should always return a null Endpoint for this case.
            return PerPriorityPanic.INVALID;
        }
        boolean totalPanic = true;
        final ImmutableMap.Builder<Integer, Boolean> perPriorityPanicBuilder = ImmutableMap.builder();
        for (Integer priority : prioritySet.priorities()) {
            final HostSet hostSet = prioritySet.hostSets().get(priority);
            final boolean isPanic =
                    normalizedTotalAvailability == 100 ? false : isHostSetInPanic(hostSet, panicThreshold);
            perPriorityPanicBuilder.put(priority, isPanic);
            totalPanic &= isPanic;
        }
        return new PerPriorityPanic(perPriorityPanicBuilder.build(), totalPanic);
    }

    private static PerPriorityLoad recalculateLoadInTotalPanic(PrioritySet prioritySet) {
        final int totalHostsCount = prioritySet.hostSets().values().stream()
                                               .map(hostSet -> hostSet.hosts().size())
                                               .reduce(0, IntMath::saturatedAdd)
                                               .intValue();
        if (totalHostsCount == 0) {
            return PerPriorityLoad.INVALID;
        }
        int totalLoad = 100;
        int firstNoEmpty = -1;
        final Map<Integer, Integer> healthyPriorityLoad = new HashMap<>();
        final Map<Integer, Integer> degradedPriorityLoad = new HashMap<>();
        for (Integer priority: prioritySet.priorities()) {
            final HostSet hostSet = prioritySet.hostSets().get(priority);
            final int hostsSize = hostSet.hosts().size();
            if (firstNoEmpty == -1 && hostsSize > 0) {
                firstNoEmpty = priority;
            }
            final int load = 100 * hostsSize / totalHostsCount;
            healthyPriorityLoad.put(priority, load);
            degradedPriorityLoad.put(priority, 0);
            totalLoad -= load;
        }
        final int remainingLoad = totalLoad;
        healthyPriorityLoad.computeIfPresent(firstNoEmpty, (k, v) -> v + remainingLoad);
        final int priorityLoadSum = priorityLoadSum(healthyPriorityLoad, degradedPriorityLoad);
        assert priorityLoadSum == 100
                : "The priority loads not summing up to 100 (" + priorityLoadSum + ')';
        return new PerPriorityLoad(healthyPriorityLoad, degradedPriorityLoad, 100);
    }

    private static boolean isHostSetInPanic(HostSet hostSet, int panicThreshold) {
        final int hostCount = hostSet.hosts().size();
        final double healthyPercent =
                hostCount == 0 ? 0 : 100.0 * hostSet.healthyHosts().size() / hostCount;
        final double degradedPercent =
                hostCount == 0 ? 0 : 100.0 * hostSet.degradedHosts().size() / hostCount;
        return healthyPercent + degradedPercent < panicThreshold;
    }

    static class PerPriorityLoad {
        final Map<Integer, Integer> healthyPriorityLoad;
        final Map<Integer, Integer> degradedPriorityLoad;
        private final int normalizedTotalAvailability;
        private final boolean forceEmptyEndpoint;

        private static final PerPriorityLoad INVALID = new PerPriorityLoad();

        private PerPriorityLoad() {
            healthyPriorityLoad = Collections.emptyMap();
            degradedPriorityLoad = Collections.emptyMap();
            normalizedTotalAvailability = 0;
            forceEmptyEndpoint = true;
        }

        PerPriorityLoad(Map<Integer, Integer> healthyPriorityLoad,
                        Map<Integer, Integer> degradedPriorityLoad,
                        int normalizedTotalAvailability) {
            this.healthyPriorityLoad = ImmutableMap.copyOf(healthyPriorityLoad);
            this.degradedPriorityLoad = ImmutableMap.copyOf(degradedPriorityLoad);
            this.normalizedTotalAvailability = normalizedTotalAvailability;
            forceEmptyEndpoint = false;
        }

        int normalizedTotalAvailability() {
            return normalizedTotalAvailability;
        }

        int getHealthy(int priority) {
            return healthyPriorityLoad.getOrDefault(priority, 0);
        }

        int getDegraded(int priority) {
            return degradedPriorityLoad.getOrDefault(priority, 0);
        }

        boolean forceEmptyEndpoint() {
            return forceEmptyEndpoint;
        }
    }

    static class PerPriorityPanic {
        final Map<Integer, Boolean> perPriorityPanic;
        private final boolean totalPanic;
        private final boolean forceEmptyEndpoint;

        static final PerPriorityPanic INVALID = new PerPriorityPanic();

        private PerPriorityPanic() {
            perPriorityPanic = Collections.emptyMap();
            forceEmptyEndpoint = true;
            totalPanic = false;
        }

        PerPriorityPanic(Map<Integer, Boolean> perPriorityPanic, boolean totalPanic) {
            this.perPriorityPanic = ImmutableMap.copyOf(perPriorityPanic);
            this.totalPanic = totalPanic;
            forceEmptyEndpoint = false;
        }

        boolean get(int priority) {
            return perPriorityPanic.getOrDefault(priority, true);
        }

        boolean totalPanic() {
            return totalPanic;
        }

        boolean forceEmptyEndpoint() {
            return forceEmptyEndpoint;
        }
    }

    static class ZoneAwareLbState {
        private final PrioritySet prioritySet;
        private final PerPriorityLoad perPriorityLoad;
        private final PerPriorityPanic perPriorityPanic;

        ZoneAwareLbState(PrioritySet prioritySet,
                         PerPriorityLoad perPriorityLoad, PerPriorityPanic perPriorityPanic) {
            this.prioritySet = prioritySet;
            this.perPriorityLoad = perPriorityLoad;
            this.perPriorityPanic = perPriorityPanic;
        }

        PerPriorityPanic perPriorityPanic() {
            return perPriorityPanic;
        }

        PrioritySet prioritySet() {
            return prioritySet;
        }

        PerPriorityLoad perPriorityLoad() {
            return perPriorityLoad;
        }

        @Nullable
        PriorityAndAvailability choosePriority(int hash) {
            if (perPriorityLoad.forceEmptyEndpoint() || perPriorityPanic.forceEmptyEndpoint()) {
                return null;
            }
            hash = hash % 100 + 1;
            int aggregatePercentageLoad = 0;
            final PerPriorityLoad perPriorityLoad = perPriorityLoad();
            for (Integer priority: prioritySet.priorities()) {
                aggregatePercentageLoad += perPriorityLoad.getHealthy(priority);
                if (hash <= aggregatePercentageLoad) {
                    return new PriorityAndAvailability(priority, HostAvailability.HEALTHY);
                }
            }
            for (Integer priority: prioritySet.priorities()) {
                aggregatePercentageLoad += perPriorityLoad.getDegraded(priority);
                if (hash <= aggregatePercentageLoad) {
                    return new PriorityAndAvailability(priority, HostAvailability.DEGRADED);
                }
            }
            throw new Error("shouldn't reach here");
        }
    }

    private static class HealthAndDegraded {
        private final int healthWeight;
        private final int degradedWeight;

        HealthAndDegraded(int healthWeight, int degradedWeight) {
            this.healthWeight = healthWeight;
            this.degradedWeight = degradedWeight;
        }
    }

    private ZoneAwareLbStateFactory() {}
}
