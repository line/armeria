/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.Objects;
import java.util.function.BiPredicate;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * A resource node representing a {@link RouteConfiguration}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 */
public final class RouteNode extends AbstractNode<RouteResourceHolder> {

    private final XdsBootstrapImpl xdsBootstrap;
    @Nullable
    private String currentName;

    RouteNode(XdsBootstrapImpl xdsBootstrap, ListenerRoot listenerRoot) {
        super(xdsBootstrap.eventLoop());
        this.xdsBootstrap = xdsBootstrap;
        listenerRoot.addListener(new ResourceWatcher<ListenerResourceHolder>() {
            @Override
            public void onChanged(ListenerResourceHolder update) {
                final HttpConnectionManager connectionManager = update.connectionManager();
                if (connectionManager == null) {
                    throw new IllegalArgumentException("Listener doesn't have a connection manager");
                }
                final String routeName;
                if (connectionManager.hasRouteConfig()) {
                    routeName = connectionManager.getRouteConfig().getName();
                } else if (connectionManager.hasRds()) {
                    routeName = connectionManager.getRds().getRouteConfigName();
                } else {
                    throw new IllegalArgumentException("A connection manager should have a" +
                                                       " RouteConfig or RDS.");
                }
                if (Objects.equals(routeName, currentName)) {
                    return;
                }
                if (currentName != null) {
                    xdsBootstrap.removeRouteWatcher(currentName, RouteNode.this);
                }
                xdsBootstrap.addRouteWatcher(routeName, RouteNode.this);
                currentName = routeName;
            }
        });
    }

    /**
     * Returns a node representation of the {@link Cluster} contained by this listener.
     */
    public ClusterNode clusterNode(BiPredicate<VirtualHost, Route> predicate) {
        return new ClusterNode(xdsBootstrap, this, predicate);
    }
}
