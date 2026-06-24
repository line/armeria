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

import static com.linecorp.armeria.xds.XdsType.LISTENER;

import java.util.List;
import java.util.Optional;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

final class ListenerFilterChainFactory {

    private final SubscriptionContext context;
    private final XdsExtensionRegistry registry;
    private final ListenerXdsResource listenerResource;

    ListenerFilterChainFactory(SubscriptionContext context, ListenerXdsResource listenerResource) {
        this.context = context;
        registry = context.extensionRegistry();
        this.listenerResource = listenerResource;
    }

    SnapshotStream<FilterChainSnapshot> resolve(FilterChain filterChain,
                                                @Nullable ConfigSource parentConfigSource) {
        final HttpConnectionManager hcm = extractHcm(filterChain);
        final SnapshotStream<TransportSocketSnapshot> tsStream =
                new TransportSocketStream(context, parentConfigSource, filterChain.getTransportSocket());

        final SnapshotStream<Optional<RouteSnapshot>> routeStream = resolveRoute(hcm, parentConfigSource);

        return SnapshotStream.combineLatest(
                tsStream, routeStream, (transportSocket, route) ->
                        new FilterChainSnapshot(filterChain.getFilterChainMatch(),
                                                transportSocket, route.orElse(null)));
    }

    SnapshotStream<Optional<RouteSnapshot>> resolveApiListenerRoute(
            @Nullable ConfigSource parentConfigSource) {
        final Listener listener = listenerResource.resource();
        if (!listener.getApiListener().hasApiListener()) {
            return SnapshotStream.empty();
        }
        final HttpConnectionManager hcm = registry.unpack(listener.getApiListener().getApiListener(),
                                                          HttpConnectionManager.class);
        return resolveRoute(hcm, parentConfigSource);
    }

    private SnapshotStream<Optional<RouteSnapshot>> resolveRoute(
            @Nullable HttpConnectionManager hcm, @Nullable ConfigSource parentConfigSource) {
        if (hcm == null) {
            return SnapshotStream.empty();
        }
        if (hcm.hasRouteConfig()) {
            final RouteConfiguration routeConfig = hcm.getRouteConfig();
            return new RouteStream(context, routeConfig, hcm).map(Optional::of);
        }
        if (hcm.hasRds()) {
            final Rds rds = hcm.getRds();
            final String routeName = rds.getRouteConfigName();
            final ConfigSource configSource =
                    context.configSourceMapper().configSource(rds.getConfigSource(), parentConfigSource);
            if (configSource == null) {
                return SnapshotStream.error(new XdsResourceException(LISTENER, listenerResource.name(),
                                                                     "config source not found"));
            }
            return new RouteStream(configSource, routeName, context, hcm).map(Optional::of);
        }
        return SnapshotStream.empty();
    }

    @Nullable
    private HttpConnectionManager extractHcm(FilterChain filterChain) {
        final List<Filter> filters = filterChain.getFiltersList();
        if (filters.isEmpty()) {
            return null;
        }
        final Filter last = filters.get(filters.size() - 1);
        return HttpConnectionManagerFactory.extractHcm(last, registry);
    }
}
