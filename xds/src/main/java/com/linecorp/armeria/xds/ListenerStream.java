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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

final class ListenerStream extends RefCountedStream<ListenerSnapshot> {

    @Nullable
    private final ListenerXdsResource listenerXdsResource;
    private final String resourceName;
    private final SubscriptionContext context;

    ListenerStream(ListenerXdsResource listenerXdsResource, SubscriptionContext context) {
        this.listenerXdsResource = listenerXdsResource;
        resourceName = listenerXdsResource.name();
        this.context = context;
    }

    ListenerStream(String resourceName, SubscriptionContext context) {
        this.resourceName = resourceName;
        this.context = context;
        listenerXdsResource = null;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<ListenerSnapshot> watcher) {
        if (listenerXdsResource != null) {
            return resource2snapshot(listenerXdsResource, null).subscribe(watcher);
        }

        final ConfigSource configSource = context.configSourceMapper().ldsConfigSource();
        if (configSource == null) {
            final XdsResourceException e =
                    new XdsResourceException(LISTENER, resourceName, "config source not found");
            return SnapshotStream.<ListenerSnapshot>error(e)
                                 .subscribe(watcher);
        }
        return new ResourceNodeAdapter<ListenerXdsResource>(configSource, context, resourceName, LISTENER)
                .switchMap(resource -> resource2snapshot(resource, configSource))
                .subscribe(watcher);
    }

    private SnapshotStream<ListenerSnapshot> resource2snapshot(ListenerXdsResource resource,
                                                               @Nullable ConfigSource parentConfigSource) {
        SnapshotStream<ListenerSnapshot> node = null;
        final HttpConnectionManager connectionManager = resource.connectionManager();
        if (connectionManager != null) {
            if (connectionManager.hasRouteConfig()) {
                final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
                node = new RouteStream(context, routeConfig)
                        .map(routeSnapshot -> new ListenerSnapshot(resource, routeSnapshot));
            } else if (connectionManager.hasRds()) {
                final Rds rds = connectionManager.getRds();
                final String routeName = rds.getRouteConfigName();
                final ConfigSource configSource =
                        context.configSourceMapper()
                               .configSource(rds.getConfigSource(), parentConfigSource);
                if (configSource == null) {
                    return SnapshotStream.error(new XdsResourceException(LISTENER, resourceName,
                                                                         "config source not found"));
                }
                node = new RouteStream(configSource, routeName, context)
                        .map(routeSnapshot -> new ListenerSnapshot(resource, routeSnapshot));
            }
        }
        if (node == null) {
            node = SnapshotStream.just(new ListenerSnapshot(resource));
        }
        return node;
    }
}
