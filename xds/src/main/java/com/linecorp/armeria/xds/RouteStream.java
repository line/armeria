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

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

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

            // Merge per_filter_config: route-config level < vhost level < route level
            final Map<String, Any> routeConfigFilterConfigs =
                    routeResource.resource().getTypedPerFilterConfigMap();
            final Map<String, Any> vhostFilterConfigs =
                    vhostResource.resource().getTypedPerFilterConfigMap();
            final Map<String, Any> routeFilterConfigs =
                    route.getTypedPerFilterConfigMap();
            final Map<String, Any> filterConfigs = FilterUtil.mergeFilterConfigs(
                    FilterUtil.mergeFilterConfigs(routeConfigFilterConfigs, vhostFilterConfigs),
                    routeFilterConfigs);

            // Extract HCM downstream filters
            final List<HttpFilter> hcmHttpFilters;
            if (listenerResource != null && listenerResource.connectionManager() != null) {
                hcmHttpFilters = listenerResource.connectionManager().getHttpFiltersList();
            } else {
                hcmHttpFilters = ImmutableList.of();
            }

            // Extract upstream HTTP filters from the Router
            final List<HttpFilter> upstreamFilters;
            if (listenerResource != null && listenerResource.router() != null) {
                upstreamFilters = listenerResource.router().getUpstreamHttpFiltersList();
            } else {
                upstreamFilters = ImmutableList.of();
            }

            // Determine retry policy
            final RetryPolicy retryPolicy = route.getRoute().getRetryPolicy();
            final RetryPolicy effectiveRetryPolicy =
                    retryPolicy == RetryPolicy.getDefaultInstance() ? null : retryPolicy;

            // Build filter streams
            final SnapshotStream<ClientPreprocessors> downstreamStream =
                    FilterUtil.buildDownstreamFilter(extensionRegistry, context,
                                                     hcmHttpFilters, filterConfigs);
            final SnapshotStream<ClientDecoration> upstreamStream =
                    FilterUtil.buildUpstreamFilter(extensionRegistry, context,
                                                   upstreamFilters, filterConfigs,
                                                   effectiveRetryPolicy);

            if (!route.getRoute().hasCluster()) {
                return SnapshotStream.combineLatest(
                        downstreamStream, upstreamStream,
                        (down, up) -> new RouteEntry(
                                route, null, index, filterConfigs, down, up))
                                     .subscribe(watcher);
            }

            // Wrap cluster registration as SnapshotStream
            final SnapshotStream<ClusterSnapshot> clusterStream =
                    w -> context.clusterManager().register(clusterName, context, w);

            return SnapshotStream.combineLatest(
                    clusterStream, downstreamStream, upstreamStream,
                    (cluster, down, up) -> new RouteEntry(
                            route, cluster, index, filterConfigs, down, up))
                                 .subscribe(watcher);
        }
    }
}
