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

import static com.linecorp.armeria.xds.client.endpoint.MetadataUtil.withFilterKeys;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.Value.KindCase;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.client.endpoint.PrioritySet.PrioritySetBuilder;
import com.linecorp.armeria.xds.client.endpoint.SubsetInfo.SubsetSelector;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetMetadataFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy;
import io.envoyproxy.envoy.config.core.v3.Metadata;

final class SubsetLoadBalancer implements LoadBalancer {

    private final ClusterSnapshot clusterSnapshot;
    private final LbSubsetConfig lbSubsetConfig;
    private final SubsetInfo subsetInfo;
    @Nullable
    private LbState lbState;
    private final Struct filterMetadata;

    SubsetLoadBalancer(ClusterSnapshot clusterSnapshot) {
        this.clusterSnapshot = clusterSnapshot;
        lbSubsetConfig = clusterSnapshot.xdsResource().resource().getLbSubsetConfig();
        subsetInfo = new SubsetInfo(lbSubsetConfig);
        filterMetadata = MetadataUtil.filterMetadata(clusterSnapshot);
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        if (lbState == null) {
            return null;
        }
        return lbState.chooseHost(ctx, filterMetadata);
    }

    @Override
    public void prioritySetUpdated(PrioritySet prioritySet) {
        lbState = new LbState(prioritySet, subsetInfo, lbSubsetConfig, clusterSnapshot);
    }

    static class LbState {

        private final PrioritySet origPrioritySet;
        private final SubsetInfo subsetInfo;
        private final LbSubsetMetadataFallbackPolicy metadataFallbackPolicy;
        @Nullable
        private ZoneAwareLoadBalancer subsetAny;
        @Nullable
        private ZoneAwareLoadBalancer subsetDefault;
        @Nullable
        private ZoneAwareLoadBalancer fallbackSubset;
        @Nullable
        private ZoneAwareLoadBalancer panicModeSubset;
        private final Map<SortedSet<String>, SubsetSelector> selectorMap;
        private final Map<Struct, ZoneAwareLoadBalancer> subsets;
        private final boolean listAsAny;
        private final boolean scaleLocalityWeight;

        private final List<Struct> fallbackMetadataList;

        LbState(PrioritySet origPrioritySet, SubsetInfo subsetInfo, LbSubsetConfig lbSubsetConfig,
                ClusterSnapshot clusterSnapshot) {
            this.origPrioritySet = origPrioritySet;
            this.subsetInfo = subsetInfo;
            listAsAny = lbSubsetConfig.getListAsAny();
            scaleLocalityWeight = lbSubsetConfig.getScaleLocalityWeight();
            metadataFallbackPolicy = lbSubsetConfig.getMetadataFallbackPolicy();
            fallbackMetadataList = MetadataUtil.fallbackMetadataList(clusterSnapshot);

            final Struct defaultSubsetMetadata = lbSubsetConfig.getDefaultSubset();
            if (lbSubsetConfig.getFallbackPolicy() != LbSubsetFallbackPolicy.NO_FALLBACK) {
                if (lbSubsetConfig.getFallbackPolicy() == LbSubsetFallbackPolicy.ANY_ENDPOINT) {
                    fallbackSubset = initSubsetAnyOnce();
                } else {
                    fallbackSubset = initSubsetDefaultOnce(defaultSubsetMetadata);
                }
            }
            if (lbSubsetConfig.getPanicModeAny()) {
                panicModeSubset = initSubsetAnyOnce();
            }
            selectorMap = initSubsetSelectorMap(subsetInfo, defaultSubsetMetadata);
            subsets = refreshSubsets();
        }

        @Nullable
        Endpoint chooseHost(ClientRequestContext ctx, Struct filterMetadata) {
            if (metadataFallbackPolicy != LbSubsetMetadataFallbackPolicy.FALLBACK_LIST) {
                return chooseHostIteration(ctx, filterMetadata);
            }
            if (fallbackMetadataList.isEmpty()) {
                return chooseHostIteration(ctx, filterMetadata);
            }
            for (Struct struct: fallbackMetadataList) {
                final Endpoint endpoint = chooseHostIteration(ctx, struct);
                if (endpoint != null) {
                    return endpoint;
                }
            }
            return null;
        }

        @Nullable
        Endpoint chooseHostIteration(ClientRequestContext ctx, Struct filterMetadata) {
            if (subsets.containsKey(filterMetadata)) {
                return subsets.get(filterMetadata).selectNow(ctx);
            }
            final Set<String> keys = filterMetadata.getFieldsMap().keySet();
            if (selectorMap.containsKey(keys)) {
                final SubsetSelector subsetSelector = selectorMap.get(keys);
                if (subsetSelector.fallbackPolicy() != LbSubsetSelectorFallbackPolicy.NOT_DEFINED) {
                    return chooseHostForSelectorFallbackPolicy(subsetSelector, ctx, filterMetadata);
                }
            }

            if (fallbackSubset != null) {
                return fallbackSubset.selectNow(ctx);
            }

            if (panicModeSubset != null) {
                return panicModeSubset.selectNow(ctx);
            }
            return null;
        }

        @Nullable
        Endpoint chooseHostForSelectorFallbackPolicy(SubsetSelector subsetSelector,
                                                     ClientRequestContext ctx, Struct filterMetadata) {
            if (subsetSelector.fallbackPolicy() == LbSubsetSelectorFallbackPolicy.ANY_ENDPOINT &&
                subsetAny != null) {
                return subsetAny.selectNow(ctx);
            }
            if (subsetSelector.fallbackPolicy() == LbSubsetSelectorFallbackPolicy.DEFAULT_SUBSET &&
                       subsetDefault != null) {
                return subsetDefault.selectNow(ctx);
            }
            if (subsetSelector.fallbackPolicy() == LbSubsetSelectorFallbackPolicy.KEYS_SUBSET) {
                final Set<String> fallbackKeysSubset = subsetSelector.fallbackKeysSubset();
                final Struct newFilterMetadata = withFilterKeys(filterMetadata, fallbackKeysSubset);
                return chooseHostIteration(ctx, newFilterMetadata);
            }
            return null;
        }

        ZoneAwareLoadBalancer initSubsetAnyOnce() {
            if (subsetAny == null) {
                subsetAny = createSubsetEntry(ignored -> true);
            }
            return subsetAny;
        }

        ZoneAwareLoadBalancer initSubsetDefaultOnce(Struct subsetMetadata) {
            if (subsetDefault == null) {
                subsetDefault = createSubsetEntry(host -> hostMatches(subsetMetadata, host));
            }
            return subsetDefault;
        }

        Map<SortedSet<String>, SubsetSelector> initSubsetSelectorMap(SubsetInfo subsetInfo,
                                                                     Struct defaultSubsetMetadata) {
            final ImmutableMap.Builder<SortedSet<String>, SubsetSelector> selectorMap = ImmutableMap.builder();
            for (SubsetSelector subsetSelector: subsetInfo.subsetSelectors()) {
                selectorMap.put(subsetSelector.keys(), subsetSelector);
                if (subsetSelector.fallbackPolicy() == LbSubsetSelectorFallbackPolicy.ANY_ENDPOINT) {
                    initSubsetAnyOnce();
                } else if (subsetSelector.fallbackPolicy() == LbSubsetSelectorFallbackPolicy.DEFAULT_SUBSET) {
                    initSubsetDefaultOnce(defaultSubsetMetadata);
                }
            }
            return selectorMap.buildKeepingLast();
        }

        Map<Struct, ZoneAwareLoadBalancer> refreshSubsets() {
            final Map<Struct, SubsetPrioritySetBuilder> prioritySets = new HashMap<>();
            for (Entry<Integer, HostSet> entry: origPrioritySet.hostSets().entrySet()) {
                processSubsets(entry.getKey(), entry.getValue(), prioritySets);
            }

            final ImmutableMap.Builder<Struct, ZoneAwareLoadBalancer> subsets = ImmutableMap.builder();
            for (Entry<Struct, SubsetPrioritySetBuilder> entry: prioritySets.entrySet()) {
                final PrioritySet.PrioritySetBuilder
                        prioritySetBuilder = new PrioritySet.PrioritySetBuilder(origPrioritySet);
                for (Integer priority: origPrioritySet.priorities()) {
                    final UpdateHostsParam param = entry.getValue().finalize(priority);
                    prioritySetBuilder.createHostSet(priority, param);
                }
                subsets.put(entry.getKey(), new ZoneAwareLoadBalancer(prioritySetBuilder.build()));
            }
            return subsets.build();
        }

        void processSubsets(int priority, HostSet hostSet, Map<Struct, SubsetPrioritySetBuilder> prioritySets) {
            for (Endpoint endpoint: hostSet.hosts()) {
                for (SubsetSelector selector: subsetInfo.subsetSelectors()) {
                    final List<Struct> allKvs =
                            extractSubsetMetadata(selector.keys(), endpoint);
                    for (Struct kvs: allKvs) {
                        prioritySets.computeIfAbsent(kvs, ignored -> new SubsetPrioritySetBuilder(
                                origPrioritySet, scaleLocalityWeight))
                                    .pushHost(priority, endpoint);
                    }
                }
            }
        }

        ZoneAwareLoadBalancer createSubsetEntry(Predicate<Endpoint> hostPredicate) {
            final PrioritySetBuilder prioritySetBuilder = new PrioritySetBuilder(origPrioritySet);
            final SubsetPrioritySetBuilder subsetPrioritySetBuilder =
                    new SubsetPrioritySetBuilder(origPrioritySet, scaleLocalityWeight);

            for (Entry<Integer, HostSet> entry: origPrioritySet.hostSets().entrySet()) {
                for (Endpoint endpoint: entry.getValue().hosts()) {
                    if (!hostPredicate.test(endpoint)) {
                        continue;
                    }
                    subsetPrioritySetBuilder.pushHost(entry.getKey(), endpoint);
                }
                final UpdateHostsParam param = subsetPrioritySetBuilder.finalize(entry.getKey());
                prioritySetBuilder.createHostSet(entry.getKey(), param);
            }

            return new ZoneAwareLoadBalancer(prioritySetBuilder.build());
        }

        boolean hostMatches(Struct metadata, Endpoint endpoint) {
            return MetadataUtil.metadataLabelMatch(
                    metadata, EndpointUtil.metadata(endpoint), SUBSET_LOAD_BALANCING_FILTER_NAME, listAsAny);
        }

        List<Struct> extractSubsetMetadata(Set<String> subsetKeys, Endpoint endpoint) {
            final Metadata metadata = EndpointUtil.metadata(endpoint);
            if (metadata == Metadata.getDefaultInstance()) {
                return Collections.emptyList();
            }
            if (!metadata.containsFilterMetadata(SUBSET_LOAD_BALANCING_FILTER_NAME)) {
                return Collections.emptyList();
            }
            final Struct filter = metadata.getFilterMetadataOrThrow(SUBSET_LOAD_BALANCING_FILTER_NAME);
            final Map<String, Value> fields = filter.getFieldsMap();
            List<Map<String, Value>> allKvs = new ArrayList<>();
            for (String subsetKey: subsetKeys) {
                if (!fields.containsKey(subsetKey)) {
                    return Collections.emptyList();
                }
                final Value value = fields.get(subsetKey);
                if (listAsAny && value.getKindCase() == KindCase.LIST_VALUE) {
                    if (allKvs.isEmpty()) {
                        for (Value innerValue: value.getListValue().getValuesList()) {
                            final HashMap<String, Value> map = new HashMap<>();
                            map.put(subsetKey, innerValue);
                            allKvs.add(map);
                        }
                    } else {
                        final List<Map<String, Value>> newKvs = new ArrayList<>();
                        for (Map<String, Value> kvMap: allKvs) {
                            for (Value innerValue: value.getListValue().getValuesList()) {
                                final Map<String, Value> newKv = new HashMap<>(kvMap);
                                newKv.put(subsetKey, innerValue);
                                newKvs.add(newKv);
                            }
                        }
                        allKvs = newKvs;
                    }
                } else {
                    if (allKvs.isEmpty()) {
                        final HashMap<String, Value> map = new HashMap<>();
                        map.put(subsetKey, value);
                        allKvs.add(map);
                    } else {
                        for (Map<String, Value> valueMap: allKvs) {
                            valueMap.put(subsetKey, value);
                        }
                    }
                }
            }
            return allKvs.stream().map(m -> Struct.newBuilder().putAllFields(m).build())
                         .collect(Collectors.toList());
        }
    }
}
