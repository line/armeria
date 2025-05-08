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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.xds.client.endpoint.DefaultLbStateFactory.isHostSetInPanic;
import static com.linecorp.armeria.xds.client.endpoint.XdsRandom.RandomHint.ROUTING_ENABLED;

import java.util.Map;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.DefaultLbStateFactory.DefaultLbState;
import com.linecorp.armeria.xds.client.endpoint.LocalityRoutingStateFactory.LocalityRoutingState;
import com.linecorp.armeria.xds.client.endpoint.LocalityRoutingStateFactory.State;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class DefaultLoadBalancer implements LoadBalancer {

    private final DefaultLbStateFactory.DefaultLbState lbState;
    private final PrioritySet prioritySet;
    @Nullable
    private final LocalityRoutingState localityRoutingState;

    DefaultLoadBalancer(PrioritySet prioritySet, @Nullable LocalCluster localCluster,
                        @Nullable PrioritySet localPrioritySet) {
        lbState = DefaultLbStateFactory.newInstance(prioritySet);
        this.prioritySet = prioritySet;
        if (localCluster != null && localPrioritySet != null) {
            localityRoutingState = localCluster.stateFactory().create(prioritySet, localPrioritySet);
        } else {
            localityRoutingState = null;
        }
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        final PrioritySet prioritySet = lbState.prioritySet();
        if (prioritySet.priorities().isEmpty()) {
            return null;
        }
        XdsRandom random = ctx.attr(ClientXdsAttributeKeys.XDS_RANDOM);
        if (random == null) {
            random = XdsRandom.DEFAULT;
        }
        final HostsSource hostsSource = hostSourceToUse(lbState, random, localityRoutingState);
        if (hostsSource == null) {
            return null;
        }
        final HostSet hostSet = prioritySet.hostSets().get(hostsSource.priority);
        if (hostSet == null) {
            // shouldn't reach here
            throw new IllegalStateException("Unable to select a priority for cluster(" +
                                            prioritySet.cluster().getName() + "), hostsSource(" +
                                            hostsSource + ')');
        }
        switch (hostsSource.sourceType) {
            case ALL_HOSTS:
                return hostSet.hostsEndpointGroup().selectNow(ctx);
            case HEALTHY_HOSTS:
                return hostSet.healthyHostsEndpointGroup().selectNow(ctx);
            case DEGRADED_HOSTS:
                return hostSet.degradedHostsEndpointGroup().selectNow(ctx);
            case LOCALITY_HEALTHY_HOSTS:
                final Map<Locality, EndpointGroup> healthyLocalities =
                        hostSet.healthyEndpointGroupPerLocality();
                final EndpointGroup healthyEndpointGroup = healthyLocalities.get(hostsSource.locality);
                if (healthyEndpointGroup != null) {
                    return healthyEndpointGroup.selectNow(ctx);
                }
                break;
            case LOCALITY_DEGRADED_HOSTS:
                final Map<Locality, EndpointGroup> degradedLocalities =
                        hostSet.degradedEndpointGroupPerLocality();
                final EndpointGroup degradedEndpointGroup = degradedLocalities.get(hostsSource.locality);
                if (degradedEndpointGroup != null) {
                    return degradedEndpointGroup.selectNow(ctx);
                }
                break;
            default:
                throw new Error();
        }
        return null;
    }

    @Nullable
    HostsSource hostSourceToUse(DefaultLbState lbState, XdsRandom random,
                                @Nullable LocalityRoutingState localityRoutingState) {
        final PriorityAndAvailability priorityAndAvailability = lbState.choosePriority(random);
        if (priorityAndAvailability == null) {
            return null;
        }
        final PrioritySet prioritySet = lbState.prioritySet();
        final int priority = priorityAndAvailability.priority;
        final HostSet hostSet = prioritySet.hostSets().get(priority);
        assert hostSet != null;
        final HostAvailability hostAvailability = priorityAndAvailability.hostAvailability;
        if (lbState.perPriorityPanic().get(priority)) {
            if (prioritySet.failTrafficOnPanic()) {
                return null;
            } else {
                return new HostsSource(priority, SourceType.ALL_HOSTS);
            }
        }

        if (prioritySet.localityWeightedBalancing()) {
            final Locality locality;
            if (hostAvailability == HostAvailability.DEGRADED) {
                locality = hostSet.chooseDegradedLocality();
            } else {
                locality = hostSet.chooseHealthyLocality();
            }
            if (locality != null) {
                return new HostsSource(priority, localitySourceType(hostAvailability), locality);
            }
        }

        // only priority 0 supports zone aware routing
        if (priority != 0 ||
            localityRoutingState == null ||
            localityRoutingState.state() == State.NO_LOCALITY_ROUTING ||
            localityRoutingState.localHostSet() == null ||
            prioritySet.routingEnabled() <= random.nextInt(100, ROUTING_ENABLED)) {
            return new HostsSource(priority, sourceType(hostAvailability));
        }

        if (isHostSetInPanic(localityRoutingState.localHostSet(), prioritySet.panicThreshold())) {
            if (prioritySet.failTrafficOnPanic()) {
                return null;
            }
            return new HostsSource(priority, sourceType(hostAvailability));
        }

        return new HostsSource(priority, localitySourceType(hostAvailability),
                               localityRoutingState.tryChooseLocalLocalityHosts(hostSet, random));
    }

    private static SourceType localitySourceType(HostAvailability hostAvailability) {
        final SourceType sourceType;
        switch (hostAvailability) {
            case HEALTHY:
                sourceType = SourceType.LOCALITY_HEALTHY_HOSTS;
                break;
            case DEGRADED:
                sourceType = SourceType.LOCALITY_DEGRADED_HOSTS;
                break;
            default:
                throw new Error();
        }
        return sourceType;
    }

    private static SourceType sourceType(HostAvailability hostAvailability) {
        final SourceType sourceType;
        switch (hostAvailability) {
            case HEALTHY:
                sourceType = SourceType.HEALTHY_HOSTS;
                break;
            case DEGRADED:
                sourceType = SourceType.DEGRADED_HOSTS;
                break;
            default:
                throw new Error();
        }
        return sourceType;
    }

    @Override
    public PrioritySet prioritySet() {
        return prioritySet;
    }

    static class PriorityAndAvailability {
        final int priority;
        final HostAvailability hostAvailability;

        PriorityAndAvailability(int priority, HostAvailability hostAvailability) {
            this.priority = priority;
            this.hostAvailability = hostAvailability;
        }
    }

    static class HostsSource {
        final int priority;
        final SourceType sourceType;
        @Nullable
        final Locality locality;

        HostsSource(int priority, SourceType sourceType) {
            this(priority, sourceType, null);
        }

        HostsSource(int priority, SourceType sourceType, @Nullable Locality locality) {
            if (sourceType == SourceType.LOCALITY_HEALTHY_HOSTS ||
                sourceType == SourceType.LOCALITY_DEGRADED_HOSTS) {
                checkArgument(locality != null, "Locality must be non-null for %s", sourceType);
            }
            this.priority = priority;
            this.sourceType = sourceType;
            this.locality = locality;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("priority", priority)
                              .add("sourceType", sourceType)
                              .add("locality", locality)
                              .toString();
        }
    }

    enum SourceType {
        ALL_HOSTS,
        HEALTHY_HOSTS,
        DEGRADED_HOSTS,
        LOCALITY_HEALTHY_HOSTS,
        LOCALITY_DEGRADED_HOSTS,
    }

    enum HostAvailability {
        HEALTHY,
        DEGRADED,
    }

    static class DistributeLoadState {
        final int totalLoad;
        final int firstAvailablePriority;

        DistributeLoadState(int totalLoad, int firstAvailablePriority) {
            this.totalLoad = totalLoad;
            this.firstAvailablePriority = firstAvailablePriority;
        }
    }
}
