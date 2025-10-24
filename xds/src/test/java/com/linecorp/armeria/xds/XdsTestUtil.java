/*
 * Copyright 2025 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

public final class XdsTestUtil {

    public static XdsLoadBalancer pollLoadBalancer(ListenerRoot root, String clusterName, Cluster expected) {
        await().untilAsserted(() -> {
            final ClusterSnapshot clusterSnapshot = findByName(root, clusterName);
            assertThat(clusterSnapshot).isNotNull();
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expected);
        });
        final ClusterSnapshot clusterSnapshot = findByName(root, clusterName);
        assertThat(clusterSnapshot).isNotNull();
        final XdsLoadBalancer selector = clusterSnapshot.loadBalancer();
        assertThat(selector).isNotNull();
        return selector;
    }

    public static XdsLoadBalancer pollLoadBalancer(
            ListenerRoot root, String clusterName, ClusterLoadAssignment expected) {
        await().untilAsserted(() -> {
            final ClusterSnapshot clusterSnapshot = findByName(root, clusterName);
            assertThat(clusterSnapshot).isNotNull();
            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot).isNotNull();
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expected);
        });
        final ClusterSnapshot clusterSnapshot = findByName(root, clusterName);
        assertThat(clusterSnapshot).isNotNull();
        final XdsLoadBalancer selector = clusterSnapshot.loadBalancer();
        assertThat(selector).isNotNull();
        return selector;
    }

    @Nullable
    private static ClusterSnapshot findByName(ListenerRoot root, String name) {
        final ListenerSnapshot listenerSnapshot = root.current();
        if (listenerSnapshot == null) {
            return null;
        }
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        for (VirtualHostSnapshot virtualHostSnapshot: routeSnapshot.virtualHostSnapshots()) {
            final List<RouteEntry> routeEntries = virtualHostSnapshot.routeEntries();
            for (RouteEntry routeEntry: routeEntries) {
                if (name.equals(routeEntry.clusterSnapshot().xdsResource().name())) {
                    return routeEntry.clusterSnapshot();
                }
            }
        }
        return null;
    }

    private XdsTestUtil() {}
}
