/*
 * Copyright 2025 LY Corporation
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
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

final class StaticResourceUtils {

    private StaticResourceUtils() {}

    static RouteResourceNode staticRoute(SubscriptionContext context, String resourceName,
                                         ListenerXdsResource primer,
                                         SnapshotWatcher<RouteSnapshot> parentWatcher,
                                         RouteConfiguration routeConfiguration) {
        final RouteResourceParser resourceParser =
                (RouteResourceParser) XdsResourceParserUtil.fromType(XdsType.ROUTE);
        final RouteXdsResource parsed = resourceParser.parse(routeConfiguration);
        final RouteResourceNode node = new RouteResourceNode(null, resourceName, context, primer,
                                                             parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }

    static VirtualHostResourceNode staticVirtualHost(
            SubscriptionContext context, String resourceName,
            RouteXdsResource primer,
            SnapshotWatcher<VirtualHostSnapshot> parentWatcher,
            int index, VirtualHost virtualHost) {
        final VirtualHostResourceNode node =
                new VirtualHostResourceNode(null, resourceName, context,
                                            primer, parentWatcher, index, STATIC);
        final VirtualHostXdsResource resource = new VirtualHostXdsResource(virtualHost);
        node.onChanged(resource);
        return node;
    }

    static ClusterResourceNode staticCluster(SubscriptionContext context, String resourceName,
                                             SnapshotWatcher<ClusterSnapshot> parentWatcher,
                                             Cluster cluster) {
        final ClusterResourceNode node = new ClusterResourceNode(null, resourceName, context,
                                                                 null, parentWatcher, STATIC);
        setClusterXdsResourceToNode(cluster, node);
        return node;
    }

    static ClusterResourceNode staticCluster(SubscriptionContext context, String resourceName,
                                             VirtualHostXdsResource primer,
                                             SnapshotWatcher<ClusterSnapshot> parentWatcher,
                                             int index, Cluster cluster) {
        final ClusterResourceNode node = new ClusterResourceNode(null, resourceName, context,
                                                                 primer, parentWatcher, index, STATIC);
        setClusterXdsResourceToNode(cluster, node);
        return node;
    }

    private static void setClusterXdsResourceToNode(Cluster cluster, ClusterResourceNode node) {
        final ClusterResourceParser resourceParser =
                (ClusterResourceParser) XdsResourceParserUtil.fromType(XdsType.CLUSTER);
        final ClusterXdsResource parsed = resourceParser.parse(cluster);
        node.onChanged(parsed);
    }

    static EndpointResourceNode staticEndpoint(SubscriptionContext context, String resourceName,
                                               ClusterXdsResource primer,
                                               SnapshotWatcher<EndpointSnapshot> parentWatcher,
                                               ClusterLoadAssignment clusterLoadAssignment) {
        final EndpointResourceParser resourceParser =
                (EndpointResourceParser) XdsResourceParserUtil.fromType(XdsType.ENDPOINT);
        final EndpointXdsResource parsed = resourceParser.parse(clusterLoadAssignment);
        final EndpointResourceNode node = new EndpointResourceNode(null, resourceName, context,
                                                                   primer, parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }
}
