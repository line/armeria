/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.HashMap;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RefreshRate;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

/**
 * A {@link ClusterTypeFactory} for STRICT_DNS cluster types that resolves
 * endpoints via DNS lookups.
 */
@UnstableApi
public final class StrictDnsClusterTypeFactory implements ClusterTypeFactory {

    private static final String NAME = "armeria.cluster.strict_dns";

    /**
     * Returns the extension name for the strict DNS cluster type.
     */
    public static String extensionName() {
        return NAME;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource, FactoryContext context) {
        final Cluster cluster = clusterXdsResource.resource();
        final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();

        final ImmutableList.Builder<SnapshotStream<LocalityLbEndpoints>> streamBuilder =
                ImmutableList.builder();
        for (LocalityLbEndpoints localityLbEndpoints : loadAssignment.getEndpointsList()) {
            for (LbEndpoint lbEndpoint : localityLbEndpoints.getLbEndpointsList()) {
                final Address address = lbEndpoint.getEndpoint().getAddress();
                if (address.hasPipe()) {
                    throw new UnsupportedOperationException(
                            "Pipe addresses are not supported for STRICT_DNS cluster type");
                }
                final EndpointGroup dnsGroup = buildDnsGroup(cluster, address.getSocketAddress());
                final SnapshotStream<LocalityLbEndpoints> stream =
                        XdsEndpointUtil.endpointGroupToStream(dnsGroup)
                                       .map(endpoints -> toLocalityLbEndpoints(
                                               localityLbEndpoints, lbEndpoint, endpoints));
                streamBuilder.add(stream);
            }
        }
        final ImmutableList<SnapshotStream<LocalityLbEndpoints>> streams = streamBuilder.build();
        if (streams.isEmpty()) {
            return SnapshotStream.just(EndpointSnapshot.of(loadAssignment));
        }
        return SnapshotStream.combineNLatest(streams)
                             .map(localities -> EndpointSnapshot.of(
                                     loadAssignment.toBuilder()
                                                   .clearEndpoints()
                                                   .addAllEndpoints(mergeLocalities(localities))
                                                   .build()));
    }

    private static LocalityLbEndpoints toLocalityLbEndpoints(
            LocalityLbEndpoints template, LbEndpoint lbTemplate, List<Endpoint> resolved) {
        final LocalityLbEndpoints.Builder builder = template.toBuilder().clearLbEndpoints();
        for (Endpoint endpoint : resolved) {
            final SocketAddress.Builder sa = SocketAddress.newBuilder()
                                                          .setAddress(endpoint.host());
            if (endpoint.port() > 0) {
                sa.setPortValue(endpoint.port());
            }
            builder.addLbEndpoints(
                    lbTemplate.toBuilder().setEndpoint(
                            io.envoyproxy.envoy.config.endpoint.v3.Endpoint
                                    .newBuilder()
                                    .setAddress(Address.newBuilder().setSocketAddress(sa))));
        }
        return builder.build();
    }

    private static List<LocalityLbEndpoints> mergeLocalities(List<LocalityLbEndpoints> localities) {
        final HashMap<LocalityLbEndpoints, LocalityLbEndpoints.Builder> merged = new HashMap<>();
        for (LocalityLbEndpoints lle : localities) {
            final LocalityLbEndpoints key = lle.toBuilder().clearLbEndpoints().build();
            merged.computeIfAbsent(key, LocalityLbEndpoints::toBuilder)
                  .addAllLbEndpoints(lle.getLbEndpointsList());
        }
        return merged.values().stream().map(LocalityLbEndpoints.Builder::build).collect(toImmutableList());
    }

    private static EndpointGroup buildDnsGroup(Cluster cluster, SocketAddress socketAddress) {
        final DnsAddressEndpointGroupBuilder builder =
                DnsAddressEndpointGroup.builder(socketAddress.getAddress());
        if (socketAddress.hasPortValue()) {
            builder.port(socketAddress.getPortValue());
        }
        if (!cluster.getRespectDnsTtl()) {
            final long refreshRateMillis =
                    cluster.hasDnsRefreshRate() ?
                    XdsCommonUtil.durationToMillis(cluster.getDnsRefreshRate()) : 5000L;
            final int refreshRateSeconds = Ints.saturatedCast(refreshRateMillis / 1000);
            builder.ttl(refreshRateSeconds, refreshRateSeconds);

            if (cluster.hasDnsFailureRefreshRate()) {
                final RefreshRate failureRefreshRate = cluster.getDnsFailureRefreshRate();
                long baseMillis = refreshRateMillis;
                long maxMillis = refreshRateMillis;
                if (failureRefreshRate.hasBaseInterval()) {
                    baseMillis = XdsCommonUtil.durationToMillis(failureRefreshRate.getBaseInterval());
                }
                if (failureRefreshRate.hasMaxInterval()) {
                    maxMillis = XdsCommonUtil.durationToMillis(failureRefreshRate.getMaxInterval());
                }
                builder.backoff(Backoff.random(baseMillis, maxMillis));
            }
        }
        return builder.build();
    }
}
