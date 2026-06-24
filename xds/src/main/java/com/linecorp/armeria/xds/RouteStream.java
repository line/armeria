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
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.xds.client.endpoint.RouterFilterFactory;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

final class RouteStream extends RefCountedStream<RouteSnapshot> {

    @Nullable
    private final RouteConfiguration routeConfiguration;
    @Nullable
    private final ConfigSource configSource;
    private final String resourceName;
    private final SubscriptionContext context;
    @Nullable
    private final HttpConnectionManager hcm;

    RouteStream(SubscriptionContext context, RouteConfiguration routeConfiguration,
                @Nullable HttpConnectionManager hcm) {
        this.context = context;
        this.routeConfiguration = routeConfiguration;
        resourceName = routeConfiguration.getName();
        configSource = null;
        this.hcm = hcm;
    }

    RouteStream(ConfigSource configSource, String resourceName, SubscriptionContext context,
                @Nullable HttpConnectionManager hcm) {
        this.configSource = configSource;
        this.resourceName = resourceName;
        this.context = context;
        routeConfiguration = null;
        this.hcm = hcm;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<RouteSnapshot> watcher) {
        if (routeConfiguration != null) {
            return new RouteSnapshotStream(new RouteXdsResource(routeConfiguration), context, hcm)
                    .subscribe(watcher);
        }
        assert configSource != null;
        final SnapshotStream<RouteSnapshot> snapshotStream =
                new ResourceNodeAdapter<RouteXdsResource>(configSource, context, resourceName, ROUTE)
                        .switchMapEager(routeResource -> new RouteSnapshotStream(routeResource, context, hcm));
        return snapshotStream.subscribe(watcher);
    }

    private static final class FilterCaches {
        final CachingStream<Map<String, Any>, ClientPreprocessors> downstream;
        final CachingStream<Map<String, Any>, ClientDecoration> upstream;
        final CachingStream<Map<String, Any>, Optional<HttpService>> server;

        FilterCaches(XdsExtensionRegistry registry, SubscriptionContext context,
                     List<HttpFilter> hcmHttpFilters, List<HttpFilter> upstreamFilters) {
            downstream = new CachingStream<>(
                    filterConfigs -> FilterUtil.buildDownstreamFilter(
                            registry, context, hcmHttpFilters, filterConfigs));
            upstream = new CachingStream<>(
                    filterConfigs -> FilterUtil.buildUpstreamFilter(
                            registry, context, upstreamFilters, filterConfigs));
            server = new CachingStream<>(
                    filterConfigs -> FilterUtil.buildDownstreamServerFilter(
                            registry, context, hcmHttpFilters, filterConfigs));
        }
    }

    private static class RouteSnapshotStream extends RefCountedStream<RouteSnapshot> {

        private final RouteXdsResource routeResource;
        private final SubscriptionContext context;
        @Nullable
        private final HttpConnectionManager hcm;

        RouteSnapshotStream(RouteXdsResource routeResource, SubscriptionContext context,
                            @Nullable HttpConnectionManager hcm) {
            this.routeResource = routeResource;
            this.context = context;
            this.hcm = hcm;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<RouteSnapshot> watcher) {
            final XdsExtensionRegistry registry = context.extensionRegistry();

            // Extract filter lists from the HCM (constant for all routes in this config)
            final List<HttpFilter> hcmHttpFilters;
            final List<HttpFilter> upstreamFilters;
            if (hcm != null) {
                hcmHttpFilters = hcm.getHttpFiltersList();
                final Router router = findRouter(hcm, registry);
                upstreamFilters = router != null ? router.getUpstreamHttpFiltersList() : ImmutableList.of();
            } else {
                hcmHttpFilters = ImmutableList.of();
                upstreamFilters = ImmutableList.of();
            }

            final FilterCaches filterCaches = new FilterCaches(registry, context,
                                                               hcmHttpFilters, upstreamFilters);

            final ImmutableList.Builder<VirtualHostStream> nodesBuilder = ImmutableList.builder();
            final RouteConfiguration routeConfiguration = routeResource.resource();
            for (int i = 0; i < routeConfiguration.getVirtualHostsList().size(); i++) {
                final VirtualHost virtualHost = routeConfiguration.getVirtualHostsList().get(i);
                final VirtualHostXdsResource vhostResource =
                        new VirtualHostXdsResource(virtualHost, routeResource.version(),
                                                   routeResource.revision());
                nodesBuilder.add(new VirtualHostStream(i, vhostResource, context,
                                                       routeResource, filterCaches));
            }
            final SnapshotStream<RouteSnapshot> routeSnapshotStream =
                    SnapshotStream.combineNLatest(nodesBuilder.build())
                                  .map(list -> new RouteSnapshot(routeResource, list));
            return routeSnapshotStream.subscribe(watcher);
        }

        @Nullable
        private static Router findRouter(@Nullable HttpConnectionManager hcm,
                                         XdsExtensionRegistry registry) {
            if (hcm == null) {
                return null;
            }
            final List<HttpFilter> httpFilters = hcm.getHttpFiltersList();
            if (httpFilters.isEmpty()) {
                return null;
            }
            final HttpFilter last = httpFilters.get(httpFilters.size() - 1);
            if (last.hasTypedConfig() &&
                RouterFilterFactory.extensionTypeUrl().equals(last.getTypedConfig().getTypeUrl())) {
                return registry.unpack(last.getTypedConfig(), Router.class);
            }
            if (RouterFilterFactory.extensionName().equals(last.getName())) {
                return Router.getDefaultInstance();
            }
            return null;
        }
    }

    private static class VirtualHostStream extends RefCountedStream<VirtualHostSnapshot> {

        private final int index;
        private final VirtualHostXdsResource resource;
        private final SubscriptionContext context;
        private final RouteXdsResource routeResource;
        private final FilterCaches filterCaches;

        VirtualHostStream(int index, VirtualHostXdsResource resource, SubscriptionContext context,
                          RouteXdsResource routeResource, FilterCaches filterCaches) {
            this.index = index;
            this.resource = resource;
            this.context = context;
            this.routeResource = routeResource;
            this.filterCaches = filterCaches;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<VirtualHostSnapshot> watcher) {
            final VirtualHost virtualHost = resource.resource();
            final ImmutableList.Builder<RouteEntryStream> routeEntryNodesBuilder = ImmutableList.builder();
            for (int i = 0; i < virtualHost.getRoutesList().size(); i++) {
                final Route route = virtualHost.getRoutesList().get(i);
                routeEntryNodesBuilder.add(new RouteEntryStream(i, route, context,
                                                                routeResource, resource, filterCaches));
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
        private final RouteXdsResource routeResource;
        private final VirtualHostXdsResource vhostResource;
        private final FilterCaches filterCaches;

        RouteEntryStream(int index, Route route, SubscriptionContext context,
                         RouteXdsResource routeResource, VirtualHostXdsResource vhostResource,
                         FilterCaches filterCaches) {
            this.index = index;
            this.route = route;
            this.context = context;
            clusterName = route.getRoute().getCluster();
            this.routeResource = routeResource;
            this.vhostResource = vhostResource;
            this.filterCaches = filterCaches;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<RouteEntry> watcher) {
            // Merge per_filter_config: route-config level < vhost level < route level
            final Map<String, Any> routeConfigFilterConfigs =
                    routeResource.resource().getTypedPerFilterConfigMap();
            final Map<String, Any> vhostFilterConfigs =
                    vhostResource.resource().getTypedPerFilterConfigMap();
            final Map<String, Any> routeFilterConfigs =
                    route.getTypedPerFilterConfigMap();
            final Map<String, Any> filterConfigs = FilterUtil.mergeFilterConfigs(
                    routeConfigFilterConfigs, vhostFilterConfigs, routeFilterConfigs);

            // Determine retry policy
            final RetryPolicy retryPolicy = route.getRoute().getRetryPolicy();
            final RetryPolicy effectiveRetryPolicy =
                    retryPolicy == RetryPolicy.getDefaultInstance() ? null : retryPolicy;

            final ClientDecoration retryDecoration =
                    FilterUtil.buildRetryDecoration(effectiveRetryPolicy);

            // Build filter streams (deduped via caches for routes with identical configs)
            final SnapshotStream<ClientPreprocessors> downstreamStream =
                    filterCaches.downstream.subscribe(filterConfigs);
            final SnapshotStream<ClientDecoration> upstreamStream =
                    filterCaches.upstream.subscribe(filterConfigs);

            final SnapshotStream<Optional<HttpService>> httpServiceStream =
                    filterCaches.server.subscribe(filterConfigs);

            if (!route.getRoute().hasCluster()) {
                return SnapshotStream.combineLatest(
                        downstreamStream, upstreamStream, httpServiceStream,
                        (down, up, httpService) -> new RouteEntry(
                                route, null, index, filterConfigs, down,
                                retryDecoration, up, httpService.orElse(null)))
                                     .subscribe(watcher);
            }

            // Wrap cluster registration as SnapshotStream
            final SnapshotStream<ClusterSnapshot> clusterStream =
                    w -> context.clusterManager().register(clusterName, context, w);

            return SnapshotStream.combineLatest(
                    clusterStream, downstreamStream, upstreamStream, httpServiceStream,
                    (cluster, down, up, httpService) -> new RouteEntry(
                            route, cluster, index, filterConfigs, down,
                            retryDecoration, up, httpService.orElse(null)))
                                 .subscribe(watcher);
        }
    }
}
