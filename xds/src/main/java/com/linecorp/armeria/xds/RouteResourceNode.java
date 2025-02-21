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
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.grpc.Status;

final class RouteResourceNode extends AbstractResourceNodeWithPrimer<RouteXdsResource> {

    private final Set<Integer> pending = new HashSet<>();
    private final List<VirtualHostSnapshot> virtualHostSnapshots = new ArrayList<>();
    private final SnapshotWatcher<RouteSnapshot> parentWatcher;
    private final VirtualHostSnapshotWatcher snapshotWatcher = new VirtualHostSnapshotWatcher();

    RouteResourceNode(@Nullable ConfigSource configSource, String resourceName,
                      SubscriptionContext context, @Nullable ListenerXdsResource primer,
                      SnapshotWatcher<RouteSnapshot> parentWatcher, ResourceNodeType resourceNodeType) {
        super(context, configSource, ROUTE, resourceName, primer, parentWatcher, resourceNodeType);
        this.parentWatcher = parentWatcher;
    }

    @Override
    public void doOnChanged(RouteXdsResource resource) {
        virtualHostSnapshots.clear();

        final RouteConfiguration routeConfiguration = resource.resource();
        for (int i = 0; i < routeConfiguration.getVirtualHostsList().size(); i++) {
            pending.add(i);
            virtualHostSnapshots.add(null);
            final VirtualHost virtualHost = routeConfiguration.getVirtualHostsList().get(i);
            final VirtualHostResourceNode childNode =
                    StaticResourceUtils.staticVirtualHost(context(), name(), resource,
                                                          snapshotWatcher, i, virtualHost);
            children().add(childNode);
        }
        if (children().isEmpty()) {
            parentWatcher.snapshotUpdated(new RouteSnapshot(resource, Collections.emptyList()));
        }
    }

    private class VirtualHostSnapshotWatcher implements SnapshotWatcher<VirtualHostSnapshot> {

        @Override
        public void snapshotUpdated(VirtualHostSnapshot newSnapshot) {
            final RouteXdsResource current = currentResource();
            if (current == null) {
                return;
            }
            if (!Objects.equals(current, newSnapshot.xdsResource().primer())) {
                return;
            }
            virtualHostSnapshots.set(newSnapshot.index(), newSnapshot);
            pending.remove(newSnapshot.index());
            // checks if all clusters for the route have reported a snapshot
            if (!pending.isEmpty()) {
                return;
            }
            parentWatcher.snapshotUpdated(
                    new RouteSnapshot(current, ImmutableList.copyOf(virtualHostSnapshots)));
        }

        @Override
        public void onError(XdsType type, Status status) {
            parentWatcher.onError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            parentWatcher.onMissing(type, resourceName);
        }
    }
}
