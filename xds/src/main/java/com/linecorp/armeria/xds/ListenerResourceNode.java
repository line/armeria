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
import io.grpc.Status;

final class ListenerResourceNode extends AbstractResourceNode<ListenerXdsResource, ListenerSnapshot> {

    @Nullable
    private RouteSnapshotWatcher snapshotWatcher;

    ListenerResourceNode(@Nullable ConfigSource configSource,
                         String resourceName, SubscriptionContext context,
                         SnapshotWatcher<ListenerSnapshot> parentWatcher, ResourceNodeType resourceNodeType) {
        super(context, configSource, LISTENER, resourceName, parentWatcher, resourceNodeType);
    }

    ListenerResourceNode(@Nullable ConfigSource configSource,
                         String resourceName, SubscriptionContext context, ResourceNodeType resourceNodeType) {
        super(context, configSource, LISTENER, resourceName, resourceNodeType);
    }

    @Override
    public void doOnChanged(ListenerXdsResource resource) {
        final RouteSnapshotWatcher previousWatcher = snapshotWatcher;
        if (previousWatcher != null) {
            previousWatcher.preClose();
        }
        snapshotWatcher = new RouteSnapshotWatcher(resource, context(), this, configSource());
        if (previousWatcher != null) {
            previousWatcher.close();
        }
    }

    @Override
    void preClose() {
        final RouteSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.preClose();
        }
        super.preClose();
    }

    @Override
    public void close() {
        final RouteSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.close();
        }
        super.close();
    }

    private static class RouteSnapshotWatcher extends AbstractNodeSnapshotWatcher<RouteSnapshot> {

        private final ListenerXdsResource resource;
        private final ListenerResourceNode parentNode;
        @Nullable
        private final RouteResourceNode node;

        RouteSnapshotWatcher(ListenerXdsResource resource, SubscriptionContext context,
                             ListenerResourceNode parentNode, @Nullable ConfigSource parentConfigSource) {
            this.resource = resource;
            this.parentNode = parentNode;

            RouteResourceNode node = null;
            final HttpConnectionManager connectionManager = resource.connectionManager();
            if (connectionManager != null) {
                if (connectionManager.hasRouteConfig()) {
                    final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
                    node = StaticResourceUtils.staticRoute(context, routeConfig.getName(),
                                                           this, routeConfig, resource.version(),
                                                           resource.revision());
                } else if (connectionManager.hasRds()) {
                    final Rds rds = connectionManager.getRds();
                    final String routeName = rds.getRouteConfigName();
                    final ConfigSource configSource =
                            context.configSourceMapper()
                                   .configSource(rds.getConfigSource(), parentConfigSource, routeName);
                    node = new RouteResourceNode(configSource, routeName, context,
                                                 this, ResourceNodeType.DYNAMIC);
                    context.subscribe(node);
                }
            }
            this.node = node;
            if (node == null) {
                parentNode.notifyOnChanged(new ListenerSnapshot(resource, null));
            }
        }

        @Override
        protected void doSnapshotUpdated(RouteSnapshot newSnapshot) {
            parentNode.notifyOnChanged(new ListenerSnapshot(resource, newSnapshot));
        }

        @Override
        protected void doOnMissing(XdsType type, String resourceName) {
            parentNode.notifyOnMissing(type, resourceName);
        }

        @Override
        protected void doOnError(XdsType type, String resourceName, Status status) {
            parentNode.notifyOnError(type, resourceName, status);
        }

        @Override
        protected void doPreClose() {
            if (node != null) {
                node.preClose();
            }
        }

        @Override
        protected void doClose() {
            if (node != null) {
                node.close();
            }
        }
    }
}
