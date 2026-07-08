/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Any;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight;

final class RouteClusterFactory {

    private final SubscriptionContext context;
    private final HcmContext hcmContext;
    private final Map<String, Any> routeFilterConfigs;
    @Nullable
    private final ClientDecoration retryDecoration;

    RouteClusterFactory(SubscriptionContext context, HcmContext hcmContext,
                        Map<String, Any> routeFilterConfigs,
                        @Nullable ClientDecoration retryDecoration) {
        this.context = context;
        this.hcmContext = hcmContext;
        this.routeFilterConfigs = routeFilterConfigs;
        this.retryDecoration = retryDecoration;
    }

    SnapshotStream<RouteClusterResolver> resolve(Route route) {
        final RouteAction routeAction = route.getRoute();
        if (routeAction.hasWeightedClusters()) {
            return resolveWeightedClusters(routeAction);
        }
        if (routeAction.hasCluster()) {
            return resolveCluster(routeAction);
        }
        return SnapshotStream.just(RouteClusterResolver.empty());
    }

    private SnapshotStream<RouteClusterResolver> resolveCluster(RouteAction routeAction) {
        final String clusterName = routeAction.getCluster();
        final Metadata routeMetadataMatch = routeAction.getMetadataMatch();
        final SnapshotStream<ClusterSnapshot> clusterStream =
                w -> context.clusterManager().register(clusterName, context, w);
        final SnapshotStream<ClientPreprocessors> downstreamStream = hcmContext.downstream(routeFilterConfigs);
        final SnapshotStream<ClientDecoration> upstreamStream = hcmContext.upstream(routeFilterConfigs);
        return SnapshotStream.combineLatest(
                clusterStream, downstreamStream, upstreamStream,
                (cs, down, up) -> {
                    return RouteClusterResolver.ofSingle(new DefaultRouteCluster(cs, routeMetadataMatch,
                                                                                 retryDecoration, down, up));
                });
    }

    private SnapshotStream<RouteClusterResolver> resolveWeightedClusters(RouteAction routeAction) {
        final WeightedCluster weightedClusters = routeAction.getWeightedClusters();
        final Metadata routeMetadataMatch = routeAction.getMetadataMatch();
        final List<ClusterWeight> clusterWeightList = weightedClusters.getClustersList();

        final List<SnapshotStream<WeightedClusterSnapshot>> wcStreams =
                new ArrayList<>(clusterWeightList.size());
        for (ClusterWeight clusterWeight : clusterWeightList) {
            final String name = clusterWeight.getName();
            final int weight = clusterWeight.getWeight().getValue();
            checkArgument(weight > 0, "weighted cluster '%s' has a weight of 0", name);
            final Metadata mergedMetadata = mergeMetadata(routeMetadataMatch, clusterWeight.getMetadataMatch());
            final SnapshotStream<ClusterSnapshot> clusterStream =
                    w -> context.clusterManager().register(name, context, w);

            final Map<String, Any> merged =
                    FilterUtil.mergeFilterConfigs(routeFilterConfigs,
                                                  clusterWeight.getTypedPerFilterConfigMap());
            final SnapshotStream<ClientPreprocessors> downstreamStream = hcmContext.downstream(merged);
            final SnapshotStream<ClientDecoration> upstreamStream = hcmContext.upstream(merged);

            wcStreams.add(SnapshotStream.combineLatest(
                    clusterStream, downstreamStream, upstreamStream,
                    (clusterSnapshot, downstreamFilters, upstreamFilter) -> {
                        return new WeightedClusterSnapshot(clusterSnapshot, weight, mergedMetadata,
                                                           retryDecoration, downstreamFilters, upstreamFilter);
                    }));
        }

        return SnapshotStream.combineNLatest(wcStreams).map(RouteClusterResolver::ofWeighted);
    }

    /**
     * Merges two {@link Metadata} instances. Values from {@code second} take precedence
     * over values from {@code first} within each filter metadata key.
     */
    static Metadata mergeMetadata(Metadata first, Metadata second) {
        if (second.equals(Metadata.getDefaultInstance())) {
            return first;
        }
        if (first.equals(Metadata.getDefaultInstance())) {
            return second;
        }
        final Metadata.Builder builder = first.toBuilder();
        for (Map.Entry<String, Struct> entry : second.getFilterMetadataMap().entrySet()) {
            final String filterName = entry.getKey();
            final Struct secondStruct = entry.getValue();
            final Struct firstStruct = first.getFilterMetadataOrDefault(filterName, null);
            if (firstStruct == null) {
                builder.putFilterMetadata(filterName, secondStruct);
            } else {
                builder.putFilterMetadata(filterName, firstStruct.toBuilder()
                                                                 .putAllFields(secondStruct.getFieldsMap())
                                                                 .build());
            }
        }
        return builder.build();
    }
}
