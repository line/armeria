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

import static com.linecorp.armeria.xds.client.endpoint.MetadataUtil.filterMetadata;
import static com.linecorp.armeria.xds.client.endpoint.MetadataUtil.findMatchedSubsetSelector;
import static com.linecorp.armeria.xds.client.endpoint.XdsEndpointUtil.convertEndpoints;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;

final class SubsetLoadBalancer implements LoadBalancer {

    private static final Logger logger = LoggerFactory.getLogger(SubsetLoadBalancer.class);

    private final EndpointGroup endpointGroup;

    SubsetLoadBalancer(PrioritySet prioritySet) {
        endpointGroup = createEndpointGroup(prioritySet);
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        return endpointGroup.selectNow(ctx);
    }

    private static EndpointGroup createEndpointGroup(PrioritySet prioritySet) {
        final ClusterSnapshot clusterSnapshot = prioritySet.clusterSnapshot();
        final Struct filterMetadata = filterMetadata(clusterSnapshot);
        if (filterMetadata.getFieldsCount() == 0) {
            // No metadata. Use the whole endpoints.
            return createEndpointGroup(prioritySet.endpoints(), clusterSnapshot);
        }

        final Cluster cluster = clusterSnapshot.xdsResource().resource();
        final LbSubsetConfig lbSubsetConfig = cluster.getLbSubsetConfig();
        if (lbSubsetConfig == LbSubsetConfig.getDefaultInstance()) {
            // Route metadata exists but no lbSubsetConfig. Use NO_FALLBACK.
            return EndpointGroup.of();
        }
        LbSubsetFallbackPolicy fallbackPolicy = lbSubsetConfig.getFallbackPolicy();
        if (!(fallbackPolicy == LbSubsetFallbackPolicy.NO_FALLBACK ||
              fallbackPolicy == LbSubsetFallbackPolicy.ANY_ENDPOINT)) {
            logger.warn("Currently, {} isn't supported. Use {}",
                        fallbackPolicy, LbSubsetFallbackPolicy.NO_FALLBACK);
            fallbackPolicy = LbSubsetFallbackPolicy.NO_FALLBACK;
        }

        if (!findMatchedSubsetSelector(lbSubsetConfig, filterMetadata)) {
            if (fallbackPolicy == LbSubsetFallbackPolicy.NO_FALLBACK) {
                return EndpointGroup.of();
            }
            return createEndpointGroup(prioritySet.endpoints(), clusterSnapshot);
        }
        final List<Endpoint> endpoints = convertEndpoints(prioritySet.endpoints(),
                                                          filterMetadata);
        if (endpoints.isEmpty()) {
            if (fallbackPolicy == LbSubsetFallbackPolicy.NO_FALLBACK) {
                return EndpointGroup.of();
            }
            return createEndpointGroup(prioritySet.endpoints(), clusterSnapshot);
        }
        return createEndpointGroup(endpoints, clusterSnapshot);
    }

    private static EndpointGroup createEndpointGroup(List<Endpoint> endpoints,
                                                     ClusterSnapshot clusterSnapshot) {
        final EndpointSelectionStrategy strategy = EndpointUtil.selectionStrategy(
                clusterSnapshot.xdsResource().resource());
        return EndpointGroup.of(strategy, endpoints);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointGroup", endpointGroup)
                          .toString();
    }
}
