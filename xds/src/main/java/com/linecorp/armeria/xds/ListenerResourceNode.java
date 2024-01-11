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

import static com.linecorp.armeria.xds.XdsType.LISTENER;

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

final class ListenerResourceNode extends AbstractResourceNode<ListenerSnapshot>
        implements SnapshotWatcher<RouteSnapshot> {

    ListenerResourceNode(@Nullable ConfigSource configSource,
                         String resourceName, XdsBootstrapImpl xdsBootstrap, @Nullable ResourceHolder primer,
                         SnapshotWatcher<ListenerSnapshot> parentNode, ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, LISTENER, resourceName, primer, parentNode, resourceNodeType);
    }

    @Override
    public void process(ResourceHolder update) {
        final ListenerResourceHolder holder = (ListenerResourceHolder) update;
        final HttpConnectionManager connectionManager = holder.connectionManager();
        if (connectionManager != null) {
            if (connectionManager.hasRouteConfig()) {
                final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
                final RouteResourceNode node =
                        StaticResourceUtils.staticRoute(xdsBootstrap(), routeConfig.getName(), holder,
                                                        this, routeConfig);
                children().add(node);
            }
            if (connectionManager.hasRds()) {
                final Rds rds = connectionManager.getRds();
                final String routeName = rds.getRouteConfigName();
                final ConfigSource configSource = rds.getConfigSource();
                final RouteResourceNode routeResourceNode =
                        new RouteResourceNode(configSource, routeName, xdsBootstrap(), holder, this,
                                              ResourceNodeType.DYNAMIC);
                children().add(routeResourceNode);
                xdsBootstrap().subscribe(configSource, routeResourceNode);
            }
        }
        if (children().isEmpty()) {
            parentNode().snapshotUpdated(new ListenerSnapshot(holder, null));
        }
    }

    @Override
    public ListenerResourceHolder current() {
        return (ListenerResourceHolder) super.current();
    }

    @Override
    public void snapshotUpdated(RouteSnapshot newSnapshot) {
        final ListenerResourceHolder current = current();
        if (current == null) {
            return;
        }
        if (!Objects.equals(newSnapshot.holder().primer(), current)) {
            return;
        }
        parentNode().snapshotUpdated(new ListenerSnapshot(current, newSnapshot));
    }
}
