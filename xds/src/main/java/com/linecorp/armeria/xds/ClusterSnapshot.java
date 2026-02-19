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

import java.util.List;
import java.util.Optional;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

/**
 * A snapshot of a {@link Cluster} resource.
 */
@UnstableApi
public final class ClusterSnapshot implements Snapshot<ClusterXdsResource> {

    private final ClusterXdsResource clusterXdsResource;
    private final TransportSocketSnapshot transportSocket;
    @Nullable
    private final EndpointSnapshot endpointSnapshot;
    private final List<TransportSocketMatchSnapshot> transportSocketMatches;
    @Nullable
    private final XdsLoadBalancer loadBalancer;

    ClusterSnapshot(ClusterXdsResource clusterXdsResource,
                    Optional<XdsLoadBalancer> loadBalancer,
                    TransportSocketSnapshot transportSocket,
                    List<TransportSocketMatchSnapshot> transportSocketMatches) {
        this.clusterXdsResource = clusterXdsResource;
        this.transportSocket = transportSocket;
        this.loadBalancer = loadBalancer.orElse(null);
        endpointSnapshot = loadBalancer.map(XdsLoadBalancer::endpointSnapshot).orElse(null);
        this.transportSocketMatches = transportSocketMatches;
    }

    @Override
    public ClusterXdsResource xdsResource() {
        return clusterXdsResource;
    }

    /**
     * A {@link EndpointSnapshot} which belong to this {@link Cluster}.
     */
    @Nullable
    public EndpointSnapshot endpointSnapshot() {
        return endpointSnapshot;
    }

    /**
     * Returns the list of {@link TransportSocketMatchSnapshot}s for this cluster.
     * These snapshots define transport socket configurations that can be conditionally
     * matched based on endpoint metadata.
     */
    @UnstableApi
    public List<TransportSocketMatchSnapshot> transportSocketMatches() {
        return transportSocketMatches;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ClusterSnapshot that = (ClusterSnapshot) object;
        return Objects.equal(clusterXdsResource, that.clusterXdsResource) &&
               Objects.equal(endpointSnapshot, that.endpointSnapshot) &&
               Objects.equal(loadBalancer, that.loadBalancer) &&
               Objects.equal(transportSocket, that.transportSocket) &&
               Objects.equal(transportSocketMatches, that.transportSocketMatches);
    }

    /**
     * The {@link XdsLoadBalancer} which allows users to select an upstream {@link Endpoint} for a given
     * {@link ClientRequestContext}. Note that the lifecycle of {@link XdsLoadBalancer} is not bound to
     * the current {@link ClusterSnapshot}, and may be updated if the cluster is updated.
     */
    @Nullable
    public XdsLoadBalancer loadBalancer() {
        return loadBalancer;
    }

    /**
     * Returns the default {@link TransportSocketSnapshot} for this cluster.
     * This transport socket is used when no {@link #transportSocketMatches()} match the endpoint.
     */
    public TransportSocketSnapshot transportSocket() {
        return transportSocket;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterXdsResource, endpointSnapshot, loadBalancer,
                                transportSocket, transportSocketMatches);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("clusterXdsResource", clusterXdsResource)
                          .add("endpointSnapshot", endpointSnapshot)
                          .add("loadBalancer", loadBalancer)
                          .add("transportSocket", transportSocket)
                          .add("transportSocketMatches", transportSocketMatches)
                          .toString();
    }
}
