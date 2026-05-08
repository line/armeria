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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.XdsType.ROUTE;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

final class RouteStream extends RefCountedStream<RouteSnapshot> {

    @Nullable
    private final RouteConfiguration routeConfiguration;
    @Nullable
    private final ConfigSource configSource;
    private final String resourceName;
    private final SubscriptionContext context;
    @Nullable
    private final ListenerXdsResource listenerResource;

    RouteStream(SubscriptionContext context, RouteConfiguration routeConfiguration,
                @Nullable ListenerXdsResource listenerResource) {
        this.context = context;
        this.routeConfiguration = routeConfiguration;
        resourceName = routeConfiguration.getName();
        configSource = null;
        this.listenerResource = listenerResource;
    }

    RouteStream(ConfigSource configSource, String resourceName, SubscriptionContext context,
                @Nullable ListenerXdsResource listenerResource) {
        this.configSource = configSource;
        this.resourceName = resourceName;
        this.context = context;
        routeConfiguration = null;
        this.listenerResource = listenerResource;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<RouteSnapshot> watcher) {
        if (routeConfiguration != null) {
            return new RouteSnapshotStream(new RouteXdsResource(routeConfiguration), context, listenerResource)
                    .subscribe(watcher);
        }
        assert configSource != null;
        final SnapshotStream<RouteSnapshot> snapshotStream =
                new ResourceNodeAdapter<RouteXdsResource>(configSource, context, resourceName, ROUTE)
                        .switchMapEager(routeResource -> new RouteSnapshotStream(routeResource, context,
                                                                                 listenerResource));
        return snapshotStream.subscribe(watcher);
    }

    private static class RouteSnapshotStream extends RefCountedStream<RouteSnapshot> {

        private final RouteXdsResource routeResource;
        private final SubscriptionContext context;
        @Nullable
        private final ListenerXdsResource listenerResource;

        RouteSnapshotStream(RouteXdsResource routeResource, SubscriptionContext context,
                            @Nullable ListenerXdsResource listenerResource) {
            this.routeResource = routeResource;
            this.context = context;
            this.listenerResource = listenerResource;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<RouteSnapshot> watcher) {
            final ImmutableList.Builder<VirtualHostStream> nodesBuilder = ImmutableList.builder();
            final RouteConfiguration routeConfiguration = routeResource.resource();
            for (int i = 0; i < routeConfiguration.getVirtualHostsList().size(); i++) {
                final VirtualHost virtualHost = routeConfiguration.getVirtualHostsList().get(i);
                final VirtualHostXdsResource vhostResource =
                        new VirtualHostXdsResource(virtualHost, routeResource.version(),
                                                   routeResource.revision());
                nodesBuilder.add(new VirtualHostStream(i, vhostResource, context,
                                                       listenerResource, routeResource));
            }
            final SnapshotStream<RouteSnapshot> routeSnapshotStream =
                    SnapshotStream.combineNLatest(nodesBuilder.build())
                                  .map(list -> new RouteSnapshot(routeResource, list));
            return routeSnapshotStream.subscribe(watcher);
        }
    }

    private static class VirtualHostStream extends RefCountedStream<VirtualHostSnapshot> {

        private final int index;
        private final VirtualHostXdsResource resource;
        private final SubscriptionContext context;
        @Nullable
        private final ListenerXdsResource listenerResource;
        private final RouteXdsResource routeResource;

        VirtualHostStream(int index, VirtualHostXdsResource resource, SubscriptionContext context,
                          @Nullable ListenerXdsResource listenerResource,
                          RouteXdsResource routeResource) {
            this.index = index;
            this.resource = resource;
            this.context = context;
            this.listenerResource = listenerResource;
            this.routeResource = routeResource;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<VirtualHostSnapshot> watcher) {
            final VirtualHost virtualHost = resource.resource();
            final ImmutableList.Builder<RouteEntryStream> routeEntryNodesBuilder = ImmutableList.builder();
            for (int i = 0; i < virtualHost.getRoutesList().size(); i++) {
                final Route route = virtualHost.getRoutesList().get(i);
                routeEntryNodesBuilder.add(new RouteEntryStream(i, route, context,
                                                                listenerResource, routeResource, resource));
            }
            final SnapshotStream<VirtualHostSnapshot> vHostStream =
                    SnapshotStream.combineNLatest(routeEntryNodesBuilder.build())
                                  .map(list -> new VirtualHostSnapshot(resource, list, index));
            return vHostStream.subscribe(watcher);
        }
    }

    private static class RouteEntryStream extends RefCountedStream<RouteEntry> {

        private final int index;
        private final Route route;
        private final SubscriptionContext context;
        private final String clusterName;
        @Nullable
        private final ListenerXdsResource listenerResource;
        private final RouteXdsResource routeResource;
        private final VirtualHostXdsResource vhostResource;

        RouteEntryStream(int index, Route route, SubscriptionContext context,
                         @Nullable ListenerXdsResource listenerResource,
                         RouteXdsResource routeResource, VirtualHostXdsResource vhostResource) {
            this.index = index;
            this.route = route;
            this.context = context;
            clusterName = route.getRoute().getCluster();
            this.listenerResource = listenerResource;
            this.routeResource = routeResource;
            this.vhostResource = vhostResource;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<RouteEntry> watcher) {
            final XdsExtensionRegistry extensionRegistry = context.extensionRegistry();
            if (!route.getRoute().hasCluster()) {
                return SnapshotStream.just(new RouteEntry(route, null, index,
                                                          listenerResource, routeResource, vhostResource,
                                                          extensionRegistry))
                                     .subscribe(watcher);
            }
            final SnapshotWatcher<ClusterSnapshot> mapped = (snapshot, t) -> {
                if (snapshot == null) {
                    watcher.onUpdate(null, t);
                    return;
                }
                watcher.onUpdate(new RouteEntry(route, snapshot, index,
                                                listenerResource, routeResource, vhostResource,
                                                extensionRegistry), null);
            };
            return context.clusterManager().register(clusterName, context, mapped);
        }
    }
}
