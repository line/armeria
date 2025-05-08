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
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class SubsetLoadBalancer implements LoadBalancer {

    private static final Logger logger = LoggerFactory.getLogger(SubsetLoadBalancer.class);

    private final PrioritySet prioritySet;
    private final LoadBalancer allEndpointsLoadBalancer;
    @Nullable
    private final LocalCluster localCluster;
    @Nullable
    private final PrioritySet localPrioritySet;

    private final Map<Struct, LoadBalancer> subsetLoadBalancers;
    private final LbSubsetConfig lbSubsetConfig;
    private final LbSubsetFallbackPolicy fallbackPolicy;

    SubsetLoadBalancer(PrioritySet prioritySet, LoadBalancer allEndpointsLoadBalancer,
                       @Nullable LocalCluster localCluster, @Nullable PrioritySet localPrioritySet) {
        this.allEndpointsLoadBalancer = allEndpointsLoadBalancer;
        this.localCluster = localCluster;
        this.localPrioritySet = localPrioritySet;

        final ClusterSnapshot clusterSnapshot = prioritySet.clusterSnapshot();
        final Cluster cluster = clusterSnapshot.xdsResource().resource();
        lbSubsetConfig = cluster.getLbSubsetConfig();
        fallbackPolicy = lbSubsetFallbackPolicy(lbSubsetConfig);

        subsetLoadBalancers = createSubsetLoadBalancers(prioritySet);
        this.prioritySet = prioritySet;
    }

    private static LbSubsetFallbackPolicy lbSubsetFallbackPolicy(LbSubsetConfig lbSubsetConfig) {
        LbSubsetFallbackPolicy fallbackPolicy = lbSubsetConfig.getFallbackPolicy();
        if (!(fallbackPolicy == LbSubsetFallbackPolicy.NO_FALLBACK ||
              fallbackPolicy == LbSubsetFallbackPolicy.ANY_ENDPOINT)) {
            logger.warn("Currently, {} isn't supported. Use {}",
                        fallbackPolicy, LbSubsetFallbackPolicy.NO_FALLBACK);
            fallbackPolicy = LbSubsetFallbackPolicy.NO_FALLBACK;
        }
        return fallbackPolicy;
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        final Struct filterMetadata = filterMetadata(ctx);
        final LoadBalancer subsetLoadBalancer = subsetLoadBalancers.get(filterMetadata);
        if (subsetLoadBalancer != null) {
            return subsetLoadBalancer.selectNow(ctx);
        }
        if (fallbackPolicy == LbSubsetFallbackPolicy.NO_FALLBACK) {
            return null;
        }
        assert fallbackPolicy == LbSubsetFallbackPolicy.ANY_ENDPOINT;
        return allEndpointsLoadBalancer.selectNow(ctx);
    }

    private Map<Struct, LoadBalancer> createSubsetLoadBalancers(PrioritySet prioritySet) {
        final ClusterSnapshot clusterSnapshot = prioritySet.clusterSnapshot();

        final Map<Struct, List<Endpoint>> endpointsPerFilterStruct = new HashMap<>();
        for (LbSubsetSelector subsetSelector: lbSubsetConfig.getSubsetSelectorsList()) {
            final ProtocolStringList keys = subsetSelector.getKeysList();
            for (Endpoint endpoint : prioritySet.endpoints()) {
                final LbEndpoint lbEndpoint = endpoint.attr(ClientXdsAttributeKeys.LB_ENDPOINT_KEY);
                assert lbEndpoint != null;
                final Struct endpointMetadata = lbEndpoint.getMetadata().getFilterMetadataOrDefault(
                        SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance());
                final Struct.Builder filteredStructBuilder = Struct.newBuilder();
                boolean allKeysFound = true;
                for (String key : keys) {
                    if (!endpointMetadata.containsFields(key)) {
                        allKeysFound = false;
                        break;
                    }
                    filteredStructBuilder.putFields(key, endpointMetadata.getFieldsOrThrow(key));
                }
                if (!allKeysFound) {
                    continue;
                }
                final Struct filteredStruct = filteredStructBuilder.build();
                endpointsPerFilterStruct.computeIfAbsent(filteredStruct, unused -> new ArrayList<>())
                                        .add(endpoint);
            }
        }
        final ImmutableMap.Builder<Struct, LoadBalancer> builder = ImmutableMap.builder();
        for (Entry<Struct, List<Endpoint>> entry : endpointsPerFilterStruct.entrySet()) {
            final PrioritySet subsetPrioritySet =
                    new PriorityStateManager(clusterSnapshot, entry.getValue()).build();
            final DefaultLoadBalancer subsetLoadBalancer =
                    new DefaultLoadBalancer(subsetPrioritySet, localCluster, localPrioritySet);
            builder.put(entry.getKey(), subsetLoadBalancer);
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("lbSubsetConfig", lbSubsetConfig)
                          .add("subsetLoadBalancers", subsetLoadBalancers)
                          .add("allEndpointsLoadBalancer", allEndpointsLoadBalancer)
                          .toString();
    }

    @Override
    public PrioritySet prioritySet() {
        return prioritySet;
    }
}
