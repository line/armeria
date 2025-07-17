/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.XdsAttributeKeys.ROUTE_METADATA_MATCH;

import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

final class RouteConfig {
    private final ListenerSnapshot listenerSnapshot;

    private final HttpPreClient httpPreClient;
    private final RpcPreClient rpcPreClient;
    private final Map<IndexPair, SelectedRoute> precomputedRoutes;
    private static final IndexPair firstPair = new IndexPair(0, 0);

    RouteConfig(ListenerSnapshot listenerSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        final ClientPreprocessors preprocessors = FilterUtil.buildDownstreamFilter(listenerSnapshot);
        httpPreClient = preprocessors.decorate(DelegatingHttpClient.INSTANCE);
        rpcPreClient = preprocessors.rpcDecorate(DelegatingRpcClient.INSTANCE);
        precomputedRoutes = routeEntries(listenerSnapshot);
    }

    private static Map<IndexPair, SelectedRoute> routeEntries(ListenerSnapshot listenerSnapshot) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return ImmutableMap.of();
        }

        final ImmutableMap.Builder<IndexPair, SelectedRoute> builder = ImmutableMap.builder();
        for (int i = 0; i < routeSnapshot.virtualHostSnapshots().size(); i++) {
            final VirtualHostSnapshot virtualHostSnapshot = routeSnapshot.virtualHostSnapshots().get(i);
            for (int j = 0; j < virtualHostSnapshot.routeEntries().size(); j++) {
                final RouteEntry routeEntry = virtualHostSnapshot.routeEntries().get(j);
                final SelectedRoute selectedRoute = new SelectedRoute(listenerSnapshot, routeSnapshot,
                                                                      virtualHostSnapshot, routeEntry);
                final IndexPair pair;
                if (i == 0 && j == 0) {
                    pair = firstPair;
                } else {
                    pair = new IndexPair(i, j);
                }
                builder.put(pair, selectedRoute);
            }
        }
        return builder.build();
    }

    ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    HttpPreClient httpPreClient() {
        return httpPreClient;
    }

    RpcPreClient rpcPreClient() {
        return rpcPreClient;
    }

    @Nullable
    SelectedRoute select(PreClientRequestContext ctx, @Nullable HttpRequest req) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return null;
        }

        for (int i = 0; i < routeSnapshot.virtualHostSnapshots().size(); i++) {
            final VirtualHostSnapshot virtualHostSnapshot = routeSnapshot.virtualHostSnapshots().get(i);
            if (!matches(req, virtualHostSnapshot)) {
                continue;
            }
            for (int j = 0; j < virtualHostSnapshot.routeEntries().size(); j++) {
                final RouteEntry routeEntry = virtualHostSnapshot.routeEntries().get(j);
                if (!matches(req, routeEntry)) {
                    continue;
                }
                ctx.setAttr(ROUTE_METADATA_MATCH, routeEntry.route().getRoute().getMetadataMatch());
                if (i == 0 && j == 0) {
                    return precomputedRoutes.get(firstPair);
                }
                return precomputedRoutes.get(new IndexPair(i, j));
            }
        }
        return null;
    }

    private static boolean matches(@Nullable HttpRequest req, RouteEntry routeEntry) {
        // matches the first entry for now
        return true;
    }

    private static boolean matches(@Nullable HttpRequest req, VirtualHostSnapshot virtualHostSnapshot) {
        // matches the first entry for now
        return true;
    }

    private static final class IndexPair {
        private final int virtualHostIndex;
        private final int clusterIndex;

        private IndexPair(int virtualHostIndex, int clusterIndex) {
            this.virtualHostIndex = virtualHostIndex;
            this.clusterIndex = clusterIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final IndexPair indexPair = (IndexPair) o;
            return virtualHostIndex == indexPair.virtualHostIndex && clusterIndex == indexPair.clusterIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(virtualHostIndex, clusterIndex);
        }
    }
}
