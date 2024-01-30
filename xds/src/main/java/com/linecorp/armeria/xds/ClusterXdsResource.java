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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

/**
 * A resource object for a {@link Cluster}.
 */
@UnstableApi
public final class ClusterXdsResource extends XdsResourceWithPrimer<ClusterXdsResource> {

    private final Cluster cluster;
    @Nullable
    private final XdsResource primer;

    ClusterXdsResource(Cluster cluster) {
        this(cluster, null);
    }

    ClusterXdsResource(Cluster cluster, @Nullable XdsResource primer) {
        this.cluster = cluster;
        this.primer = primer;
    }

    @Override
    ClusterXdsResource withPrimer(@Nullable XdsResource primer) {
        if (primer == null) {
            return this;
        }
        return new ClusterXdsResource(cluster, primer);
    }

    @Override
    XdsResource primer() {
        return primer;
    }

    @Nullable
    UpstreamTlsContext upstreamTlsContext() {
        if (cluster.hasTransportSocket()) {
            final String transportSocketName = cluster.getTransportSocket().getName();
            checkArgument("envoy.transport_sockets.tls".equals(transportSocketName),
                          "Unexpected tls transport socket name '%s'", transportSocketName);
            try {
                return cluster.getTransportSocket().getTypedConfig()
                              .unpack(UpstreamTlsContext.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Error unpacking tls context", e);
            }
        }
        return null;
    }

    @Override
    public XdsType type() {
        return XdsType.CLUSTER;
    }

    @Override
    public Cluster resource() {
        return cluster;
    }

    @Override
    public String name() {
        return cluster.getName();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ClusterXdsResource that = (ClusterXdsResource) object;
        return Objects.equal(cluster, that.cluster) && Objects.equal(
                primer, that.primer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(cluster, primer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("cluster", cluster)
                          .add("primer", primer)
                          .toString();
    }
}
