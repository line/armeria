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

import static com.linecorp.armeria.xds.XdsType.ROUTE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.grpc.Status;

final class RouteResourceNode extends AbstractResourceNode<RouteXdsResource, RouteSnapshot> {

    @Nullable
    private VirtualHostSnapshotWatcher snapshotWatcher;

    RouteResourceNode(@Nullable ConfigSource configSource, String resourceName,
                      SubscriptionContext context, SnapshotWatcher<RouteSnapshot> parentWatcher,
                      ResourceNodeType resourceNodeType) {
        super(context, configSource, ROUTE, resourceName, parentWatcher, resourceNodeType);
    }

    @Override
    public void doOnChanged(RouteXdsResource resource) {
        final VirtualHostSnapshotWatcher previousWatcher = snapshotWatcher;
        snapshotWatcher = new VirtualHostSnapshotWatcher(resource, context(), this);
        if (previousWatcher != null) {
            previousWatcher.close();
        }
    }

    @Override
    public void close() {
        final VirtualHostSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.close();
        }
        super.close();
    }

    private static class VirtualHostSnapshotWatcher
            implements SnapshotWatcher<VirtualHostSnapshot>, SafeCloseable {

        private final List<VirtualHostSnapshot> virtualHostSnapshots = new ArrayList<>();
        private final Set<Integer> pending = new HashSet<>();
        private final RouteXdsResource resource;
        private final RouteResourceNode parentNode;
        private final List<VirtualHostResourceNode> nodes;
        private boolean closed;

        VirtualHostSnapshotWatcher(RouteXdsResource resource, SubscriptionContext context,
                                   RouteResourceNode parentNode) {
            this.resource = resource;
            this.parentNode = parentNode;

            final ImmutableList.Builder<VirtualHostResourceNode> nodesBuilder = ImmutableList.builder();
            final RouteConfiguration routeConfiguration = resource.resource();
            for (int i = 0; i < routeConfiguration.getVirtualHostsList().size(); i++) {
                pending.add(i);
                virtualHostSnapshots.add(null);
                final VirtualHost virtualHost = routeConfiguration.getVirtualHostsList().get(i);
                final VirtualHostResourceNode childNode =
                        StaticResourceUtils.staticVirtualHost(context, virtualHost.getName(),
                                                              this, i, virtualHost);
                nodesBuilder.add(childNode);
            }
            nodes = nodesBuilder.build();
            if (nodes.isEmpty()) {
                parentNode.notifyOnChanged(new RouteSnapshot(resource, Collections.emptyList()));
            }
        }

        @Override
        public void snapshotUpdated(VirtualHostSnapshot newSnapshot) {
            if (closed) {
                return;
            }
            virtualHostSnapshots.set(newSnapshot.index(), newSnapshot);
            pending.remove(newSnapshot.index());
            // checks if all clusters for the route have reported a snapshot
            if (!pending.isEmpty()) {
                return;
            }
            parentNode.notifyOnChanged(new RouteSnapshot(resource, ImmutableList.copyOf(virtualHostSnapshots)));
        }

        @Override
        public void onError(XdsType type, Status status) {
            if (closed) {
                return;
            }
            parentNode.notifyOnError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            if (closed) {
                return;
            }
            parentNode.notifyOnMissing(type, resourceName);
        }

        @Override
        public void close() {
            closed = true;
            for (VirtualHostResourceNode childNode : nodes) {
                childNode.close();
            }
        }
    }
}
