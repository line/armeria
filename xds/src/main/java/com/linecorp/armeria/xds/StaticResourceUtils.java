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

import static com.linecorp.armeria.xds.ResourceNodeType.STATIC;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

final class StaticResourceUtils {

    private StaticResourceUtils() {}

    static RouteResourceNode staticRoute(SubscriptionContext context, String resourceName,
                                         SnapshotWatcher<RouteSnapshot> parentWatcher,
                                         RouteConfiguration routeConfiguration, String version, long revision) {
        final RouteResourceParser resourceParser =
                (RouteResourceParser) XdsResourceParserUtil.fromType(XdsType.ROUTE);
        final RouteXdsResource parsed = resourceParser.parse(routeConfiguration, version, revision);
        final RouteResourceNode node = new RouteResourceNode(null, resourceName, context,
                                                             parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }

    static VirtualHostResourceNode staticVirtualHost(
            SubscriptionContext context, String resourceName,
            SnapshotWatcher<VirtualHostSnapshot> parentWatcher,
            int index, VirtualHost virtualHost, String version, long revision) {
        final VirtualHostResourceNode node =
                new VirtualHostResourceNode(null, resourceName, context, parentWatcher, index, STATIC);
        final VirtualHostXdsResource resource = new VirtualHostXdsResource(virtualHost, version, revision);
        node.onChanged(resource);
        return node;
    }

    static EndpointResourceNode staticEndpoint(SubscriptionContext context, String resourceName,
                                               SnapshotWatcher<EndpointSnapshot> parentWatcher,
                                               ClusterLoadAssignment clusterLoadAssignment, String version,
                                               long revision) {
        final EndpointResourceParser resourceParser =
                (EndpointResourceParser) XdsResourceParserUtil.fromType(XdsType.ENDPOINT);
        final EndpointXdsResource parsed = resourceParser.parse(clusterLoadAssignment, version, revision);
        final EndpointResourceNode node = new EndpointResourceNode(null, resourceName, context,
                                                                   parentWatcher, STATIC);
        node.onChanged(parsed);
        return node;
    }
}
