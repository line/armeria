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
    @Nullable
    private final EndpointSnapshot endpointSnapshot;
    private final int index;

    @Nullable
    private XdsLoadBalancer loadBalancer;

    ClusterSnapshot(ClusterXdsResource clusterXdsResource,
                    @Nullable EndpointSnapshot endpointSnapshot, int index) {
        this.clusterXdsResource = clusterXdsResource;
        this.endpointSnapshot = endpointSnapshot;
        this.index = index;
    }

    ClusterSnapshot(ClusterXdsResource clusterXdsResource) {
        this(clusterXdsResource, null, -1);
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

    void loadBalancer(XdsLoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    /**
     * The {@link XdsLoadBalancer} which allows users to select an upstream {@link Endpoint} for a given
     * {@link ClientRequestContext}.
     */
    @Nullable
    public XdsLoadBalancer loadBalancer() {
        return loadBalancer;
    }

    int index() {
        return index;
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
               Objects.equal(endpointSnapshot, that.endpointSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterXdsResource, endpointSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("clusterXdsResource", clusterXdsResource)
                          .add("endpointSnapshot", endpointSnapshot)
                          .toString();
    }
}
