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

import java.util.Optional;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
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
        final SnapshotStream<TransportSocketSnapshot> tsStream =
                new TransportSocketStream(context, parentConfigSource, filterChain.getTransportSocket());

        final HcmContext hcmContext = HcmContext.from(filterChain, registry, context);
        if (hcmContext == null) {
            return tsStream.map(transportSocket ->
                    new FilterChainSnapshot(filterChain.getFilterChainMatch(),
                                            transportSocket, null));
        }
        final SnapshotStream<Optional<RouteSnapshot>> routeStream =
                resolveRoute(hcmContext, parentConfigSource);

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
        return resolveRoute(new HcmContext(hcm, registry, context), parentConfigSource);
    }

    private SnapshotStream<Optional<RouteSnapshot>> resolveRoute(
            HcmContext hcmContext, @Nullable ConfigSource parentConfigSource) {
        final HttpConnectionManager hcm = hcmContext.hcm();
        if (hcm.hasRouteConfig()) {
            final RouteConfiguration routeConfig = hcm.getRouteConfig();
            return new RouteStream(context, routeConfig, hcmContext).map(Optional::of);
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
            return new RouteStream(configSource, routeName, context, hcmContext).map(Optional::of);
        }
        return SnapshotStream.empty();
    }
}
