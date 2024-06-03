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

import java.util.concurrent.ThreadLocalRandom;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

final class EndpointUtil {

    static Locality locality(Endpoint endpoint) {
        final LocalityLbEndpoints localityLbEndpoints = localityLbEndpoints(endpoint);
        return localityLbEndpoints.hasLocality() ? localityLbEndpoints.getLocality()
                                                 : Locality.getDefaultInstance();
    }

    static CoarseHealth coarseHealth(Endpoint endpoint) {
        final LbEndpoint lbEndpoint = lbEndpoint(endpoint);
        switch (lbEndpoint.getHealthStatus()) {
            // Assume UNKNOWN means health check wasn't performed
            case UNKNOWN:
            case HEALTHY:
                return CoarseHealth.HEALTHY;
            case DEGRADED:
                return CoarseHealth.DEGRADED;
            default:
                return CoarseHealth.UNHEALTHY;
        }
    }

    static int hash(ClientRequestContext ctx) {
        if (ctx.hasAttr(XdsAttributesKeys.SELECTION_HASH)) {
            final Integer selectionHash = ctx.attr(XdsAttributesKeys.SELECTION_HASH);
            assert selectionHash != null;
            return Math.max(0, selectionHash);
        }
        return ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
    }

    static int priority(Endpoint endpoint) {
        return localityLbEndpoints(endpoint).getPriority();
    }

    static boolean hasLocalityLoadBalancingWeight(Endpoint endpoint) {
        return localityLbEndpoints(endpoint).hasLoadBalancingWeight();
    }

    static int localityLoadBalancingWeight(Endpoint endpoint) {
        return localityLbEndpoints(endpoint).getLoadBalancingWeight().getValue();
    }

    private static LbEndpoint lbEndpoint(Endpoint endpoint) {
        final LbEndpoint lbEndpoint = endpoint.attr(XdsAttributesKeys.LB_ENDPOINT_KEY);
        assert lbEndpoint != null;
        return lbEndpoint;
    }

    private static LocalityLbEndpoints localityLbEndpoints(Endpoint endpoint) {
        final LocalityLbEndpoints localityLbEndpoints = endpoint.attr(
                XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY);
        assert localityLbEndpoints != null;
        return localityLbEndpoints;
    }

    static EndpointSelectionStrategy selectionStrategy(Cluster cluster) {
        switch (cluster.getLbPolicy()) {
            case ROUND_ROBIN:
                return EndpointSelectionStrategy.weightedRoundRobin();
            case RANDOM:
                return EndpointSelectionStrategy.roundRobin();
            case RING_HASH:
                // implementing this is trivial so it will be done separately
            default:
                return EndpointSelectionStrategy.weightedRoundRobin();
        }
    }

    static int overProvisionFactor(ClusterLoadAssignment clusterLoadAssignment) {
        if (!clusterLoadAssignment.hasPolicy()) {
            return 140;
        }
        final Policy policy = clusterLoadAssignment.getPolicy();
        return policy.hasOverprovisioningFactor() ? policy.getOverprovisioningFactor().getValue() : 140;
    }

    static boolean weightedPriorityHealth(ClusterLoadAssignment clusterLoadAssignment) {
        return clusterLoadAssignment.hasPolicy() ?
               clusterLoadAssignment.getPolicy().getWeightedPriorityHealth() : false;
    }

    static int panicThreshold(Cluster cluster) {
        if (!cluster.hasCommonLbConfig()) {
            return 50;
        }
        final CommonLbConfig commonLbConfig = cluster.getCommonLbConfig();
        if (!commonLbConfig.hasHealthyPanicThreshold()) {
            return 50;
        }
        return Math.min((int) Math.round(commonLbConfig.getHealthyPanicThreshold().getValue()), 100);
    }

    enum CoarseHealth {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
    }

    private EndpointUtil() {}
}
