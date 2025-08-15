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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

final class RouteConfig {
    private final ListenerSnapshot listenerSnapshot;

    private final HttpPreClient httpPreClient;
    private final RpcPreClient rpcPreClient;
    private final List<List<SelectedRoute>> precomputedRoutes;
    private final VirtualHostMatcher virtualHostMatcher;

    RouteConfig(ListenerSnapshot listenerSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        final ClientPreprocessors preprocessors = FilterUtil.buildDownstreamFilter(listenerSnapshot);
        httpPreClient = preprocessors.decorate(DelegatingHttpClient.INSTANCE);
        rpcPreClient = preprocessors.rpcDecorate(DelegatingRpcClient.INSTANCE);
        precomputedRoutes = precomputeRoutes(listenerSnapshot);
        virtualHostMatcher = new VirtualHostMatcher(listenerSnapshot);
    }

    private static List<List<SelectedRoute>> precomputeRoutes(ListenerSnapshot listenerSnapshot) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return ImmutableList.of();
        }

        final int vhostsSz = routeSnapshot.virtualHostSnapshots().size();
        final ImmutableList.Builder<List<SelectedRoute>> vHostsListBuilder =
                ImmutableList.builderWithExpectedSize(vhostsSz);
        for (int i = 0; i < vhostsSz; i++) {
            final VirtualHostSnapshot virtualHostSnapshot = routeSnapshot.virtualHostSnapshots().get(i);
            assert virtualHostSnapshot.index() == i;
            final int routesSz = virtualHostSnapshot.routeEntries().size();
            final ImmutableList.Builder<SelectedRoute> routesListBuilder =
                    ImmutableList.builderWithExpectedSize(routesSz);
            for (int j = 0; j < routesSz; j++) {
                final RouteEntry routeEntry = virtualHostSnapshot.routeEntries().get(j);
                assert j == routeEntry.index();
                final SelectedRoute selectedRoute = new SelectedRoute(listenerSnapshot, routeSnapshot,
                                                                      virtualHostSnapshot, routeEntry);
                routesListBuilder.add(selectedRoute);
            }
            vHostsListBuilder.add(routesListBuilder.build());
        }
        return vHostsListBuilder.build();
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
    SelectedRoute select(PreClientRequestContext ctx) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return null;
        }
        final VirtualHostSnapshot virtualHostSnapshot = virtualHostMatcher.find(ctx);
        if (virtualHostSnapshot == null) {
            return null;
        }
        final List<SelectedRoute> selectedRoutes = precomputedRoutes.get(virtualHostSnapshot.index());
        for (SelectedRoute selectedRoute : selectedRoutes) {
            if (selectedRoute.routeEntryMatcher().matches(ctx)) {
                ctx.setAttr(XdsAttributeKeys.ROUTE_METADATA_MATCH,
                            selectedRoute.routeEntry().route().getRoute().getMetadataMatch());
                return selectedRoute;
            }
        }
        return null;
    }
}
