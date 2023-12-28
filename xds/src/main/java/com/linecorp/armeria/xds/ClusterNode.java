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
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A resource node representing a {@link Cluster}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 */
public final class ClusterNode extends AbstractNode<ClusterResourceHolder> {

    @Nullable
    private String currentName;
    private final XdsBootstrapImpl xdsBootstrap;

    ClusterNode(XdsBootstrapImpl xdsBootstrap, RouteNode routeNode,
                BiPredicate<VirtualHost, Route> predicate) {
        super(xdsBootstrap.eventLoop());
        this.xdsBootstrap = xdsBootstrap;
        routeNode.addListener(new ResourceWatcher<RouteResourceHolder>() {

            @Override
            public void onChanged(RouteResourceHolder update) {
                final RouteConfiguration routeConfiguration = update.data();
                for (VirtualHost virtualHost: routeConfiguration.getVirtualHostsList()) {
                    for (Route route: virtualHost.getRoutesList()) {
                        if (!predicate.test(virtualHost, route)) {
                            continue;
                        }
                        if (route.getActionCase() != ActionCase.ROUTE || !route.hasRoute()) {
                            return;
                        }
                        final RouteAction routeAction = route.getRoute();
                        if (!routeAction.hasCluster()) {
                            return;
                        }
                        final String clusterName = routeAction.getCluster();
                        if (Objects.equals(currentName, clusterName)) {
                            return;
                        }
                        if (currentName != null) {
                            xdsBootstrap.removeClusterWatcher(currentName, ClusterNode.this);
                        }
                        xdsBootstrap.addClusterWatcher(clusterName, ClusterNode.this);
                        currentName = clusterName;
                    }
                }
            }
        });
    }

    /**
     * Returns a node representation of the {@link ClusterLoadAssignment} contained by this listener.
     */
    public EndpointNode endpointNode() {
        return new EndpointNode(xdsBootstrap, this);
    }
}
