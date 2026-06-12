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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.nio.file.Paths;
import java.util.List;

import com.google.common.base.Predicates;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.internal.XdsEndpoint;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class XdsEndpointUtil {

    static EndpointGroup maybeHealthChecked(EndpointGroup delegate, Cluster cluster) {
        if (!cluster.getHealthChecksList().isEmpty()) {
            // multiple health-checks aren't supported
            final HealthCheck healthCheck = cluster.getHealthChecksList().get(0);
            if (healthCheck.hasHttpHealthCheck()) {
                final HttpHealthCheck httpHealthCheck = healthCheck.getHttpHealthCheck();
                return new XdsHealthCheckedEndpointGroupBuilder(delegate, cluster, httpHealthCheck)
                        .healthCheckedEndpointPredicate(Predicates.alwaysTrue())
                        .build();
            }
        }
        return delegate;
    }

    static List<Endpoint> convertLoadAssignment(
            ClusterLoadAssignment clusterLoadAssignment,
            TransportSocketSnapshot transportSocket,
            List<TransportSocketMatchSnapshot> transportSocketMatches) {
        return clusterLoadAssignment
                .getEndpointsList().stream()
                .flatMap(localityLbEndpoints -> localityLbEndpoints
                        .getLbEndpointsList()
                        .stream()
                        .map(lbEndpoint -> {
                            final TransportSocketSnapshot matched =
                                    TransportSocketMatchUtil.selectTransportSocket(
                                            transportSocket, transportSocketMatches,
                                            lbEndpoint, localityLbEndpoints);
                            return XdsEndpoint.of(convertToEndpoint(lbEndpoint), localityLbEndpoints,
                                                  lbEndpoint, matched)
                                              .endpoint();
                        }))
                .collect(toImmutableList());
    }

    static Endpoint convertToEndpoint(LbEndpoint lbEndpoint) {
        final Address address = lbEndpoint.getEndpoint().getAddress();

        if (address.hasPipe()) {
            final String pipePath = Paths.get(address.getPipe().getPath()).toAbsolutePath().toString();
            return DomainSocketAddress.of(pipePath).asEndpoint();
        }

        final SocketAddress socketAddress = address.getSocketAddress();
        final String hostname = lbEndpoint.getEndpoint().getHostname();
        final Endpoint endpoint;
        if (!Strings.isNullOrEmpty(hostname)) {
            endpoint = Endpoint.of(hostname)
                               .withIpAddr(socketAddress.getAddress());
        } else {
            endpoint = Endpoint.of(socketAddress.getAddress());
        }
        if (socketAddress.hasPortValue()) {
            return endpoint.withPort(socketAddress.getPortValue());
        }
        return endpoint;
    }

    static SnapshotStream<List<Endpoint>> endpointGroupToStream(EndpointGroup group) {
        return watcher -> {
            group.addListener(endpoints -> watcher.onUpdate(endpoints, null), true);
            return group::closeAsync;
        };
    }

    private XdsEndpointUtil() {}
}
