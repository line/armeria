/*
 * Copyright 2024 LINE Corporation
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

import static com.linecorp.armeria.xds.ResourceNodeType.STATIC;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

final class StaticResourceUtils {

    private StaticResourceUtils() {}

    static RouteResourceNode staticRoute(XdsBootstrapImpl xdsBootstrap, String resourceName,
                                         ListenerResourceHolder primer,
                                         SnapshotWatcher<RouteSnapshot> parentWatcher,
                                         RouteConfiguration routeConfiguration) {
        final RouteResourceParser resourceParser =
                (RouteResourceParser) XdsResourceParserUtil.fromType(XdsType.ROUTE);
        final RouteResourceHolder parsed = resourceParser.parse(routeConfiguration);
        final RouteResourceNode node = new RouteResourceNode(null, resourceName, xdsBootstrap, primer,
                                                             parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }

    static ClusterResourceNode staticCluster(XdsBootstrapImpl xdsBootstrap, String resourceName,
                                             SnapshotWatcher<ClusterSnapshot> parentWatcher,
                                             Cluster cluster) {
        final ClusterResourceParser resourceParser =
                (ClusterResourceParser) XdsResourceParserUtil.fromType(XdsType.CLUSTER);
        final ClusterResourceHolder parsed = resourceParser.parse(cluster);
        final ClusterResourceNode node = new ClusterResourceNode(null, resourceName, xdsBootstrap,
                                                                 null, parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }

    static EndpointResourceNode staticEndpoint(XdsBootstrapImpl xdsBootstrap, String resourceName,
                                               ClusterResourceHolder primer,
                                               SnapshotWatcher<EndpointSnapshot> parentWatcher,
                                               ClusterLoadAssignment clusterLoadAssignment) {
        final EndpointResourceParser resourceParser =
                (EndpointResourceParser) XdsResourceParserUtil.fromType(XdsType.ENDPOINT);
        final EndpointResourceHolder parsed = resourceParser.parse(clusterLoadAssignment);
        final EndpointResourceNode node = new EndpointResourceNode(null, resourceName, xdsBootstrap,
                                                                   primer, parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }
}
