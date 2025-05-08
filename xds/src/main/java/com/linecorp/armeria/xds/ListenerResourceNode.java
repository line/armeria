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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

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

    @Override
    public void doOnChanged(ListenerXdsResource resource) {
        final RouteSnapshotWatcher previousWatcher = snapshotWatcher;
        snapshotWatcher = new RouteSnapshotWatcher(resource, context(), this,
                                                   configSource());
        if (previousWatcher != null) {
            previousWatcher.close();
        }
    }

    @Override
    public void close() {
        final RouteSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.close();
        }
        super.close();
    }

    private static class RouteSnapshotWatcher implements SnapshotWatcher<RouteSnapshot>, SafeCloseable {

        private final ListenerXdsResource resource;
        private final ListenerResourceNode parentNode;
        @Nullable
        private final RouteResourceNode node;
        private boolean closed;

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
                                                           this, routeConfig);
                } else if (connectionManager.hasRds()) {
                    final Rds rds = connectionManager.getRds();
                    final String routeName = rds.getRouteConfigName();
                    final ConfigSource configSource =
                            context.configSourceMapper().withParentConfigSource(parentConfigSource)
                                   .rdsConfigSource(rds.getConfigSource(), routeName);
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
        public void snapshotUpdated(RouteSnapshot newSnapshot) {
            if (closed) {
                return;
            }
            parentNode.notifyOnChanged(new ListenerSnapshot(resource, newSnapshot));
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            if (closed) {
                return;
            }
            parentNode.notifyOnMissing(type, resourceName);
        }

        @Override
        public void onError(XdsType type, Status status) {
            if (closed) {
                return;
            }
            parentNode.notifyOnError(type, status);
        }

        @Override
        public void close() {
            closed = true;
            if (node != null) {
                node.close();
            }
        }
    }
}
