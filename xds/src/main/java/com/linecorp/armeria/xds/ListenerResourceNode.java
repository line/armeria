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
import io.grpc.Status;

final class ListenerResourceNode extends AbstractResourceNode<ListenerXdsResource> {

    private final RouteResourceWatcher snapshotWatcher = new RouteResourceWatcher();
    private final SnapshotWatcher<ListenerSnapshot> parentWatcher;

    ListenerResourceNode(@Nullable ConfigSource configSource,
                         String resourceName, XdsBootstrapImpl xdsBootstrap,
                         SnapshotWatcher<ListenerSnapshot> parentWatcher, ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, LISTENER, resourceName, parentWatcher, resourceNodeType);
        this.parentWatcher = parentWatcher;
    }

    @Override
    public void doOnChanged(ListenerXdsResource resource) {
        final HttpConnectionManager connectionManager = resource.connectionManager();
        if (connectionManager != null) {
            if (connectionManager.hasRouteConfig()) {
                final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
                final RouteResourceNode node =
                        StaticResourceUtils.staticRoute(xdsBootstrap(), routeConfig.getName(), resource,
                                                        snapshotWatcher, routeConfig);
                children().add(node);
            }
            if (connectionManager.hasRds()) {
                final Rds rds = connectionManager.getRds();
                final String routeName = rds.getRouteConfigName();
                final ConfigSource configSource = rds.getConfigSource();
                final RouteResourceNode routeResourceNode =
                        new RouteResourceNode(configSource, routeName, xdsBootstrap(), resource,
                                              snapshotWatcher, ResourceNodeType.DYNAMIC);
                children().add(routeResourceNode);
                xdsBootstrap().subscribe(routeResourceNode);
            }
        }
        if (children().isEmpty()) {
            parentWatcher.snapshotUpdated(new ListenerSnapshot(resource, null));
        }
    }

    private class RouteResourceWatcher implements SnapshotWatcher<RouteSnapshot> {

        @Override
        public void snapshotUpdated(RouteSnapshot newSnapshot) {
            final ListenerXdsResource current = currentResource();
            if (current == null) {
                return;
            }
            if (!Objects.equals(newSnapshot.xdsResource().primer(), current)) {
                return;
            }
            parentWatcher.snapshotUpdated(new ListenerSnapshot(current, newSnapshot));
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            parentWatcher.onMissing(type, resourceName);
        }

        @Override
        public void onError(XdsType type, Status status) {
            parentWatcher.onError(type, status);
        }
    }
}
