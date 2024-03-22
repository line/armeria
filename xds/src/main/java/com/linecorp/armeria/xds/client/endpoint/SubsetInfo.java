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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ProtocolStringList;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy;

class SubsetInfo {

    private final Set<SubsetSelector> subsetSelectors;

    SubsetInfo(LbSubsetConfig config) {
        final Set<SubsetSelector> subsetSelectors = new HashSet<>();
        for (LbSubsetSelector selector : config.getSubsetSelectorsList()) {
            final ProtocolStringList keys = selector.getKeysList();
            if (keys.isEmpty()) {
                continue;
            }
            subsetSelectors.add(new SubsetSelector(selector.getKeysList(), selector.getFallbackPolicy(),
                                                   selector.getFallbackKeysSubsetList()));
        }
        this.subsetSelectors = ImmutableSet.copyOf(subsetSelectors);
    }

    Set<SubsetSelector> subsetSelectors() {
        return subsetSelectors;
    }

    static class SubsetSelector {

        private final SortedSet<String> keys;
        private final LbSubsetSelectorFallbackPolicy fallbackPolicy;
        private final Set<String> fallbackKeysSubset;

        SubsetSelector(List<String> keys, LbSubsetSelectorFallbackPolicy fallbackPolicy,
                       List<String> fallbackKeysSubsetList) {
            this.keys = new TreeSet<>(keys);
            this.fallbackPolicy = fallbackPolicy;
            fallbackKeysSubset = ImmutableSet.copyOf(fallbackKeysSubsetList);
        }

        Set<String> fallbackKeysSubset() {
            return fallbackKeysSubset;
        }

        LbSubsetSelectorFallbackPolicy fallbackPolicy() {
            return fallbackPolicy;
        }

        SortedSet<String> keys() {
            return keys;
        }
    }
}
