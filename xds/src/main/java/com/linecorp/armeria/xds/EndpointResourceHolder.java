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

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A cluster object for a {@link ClusterLoadAssignment}.
 */
public final class EndpointResourceHolder extends AbstractResourceHolder {

    private final ClusterLoadAssignment clusterLoadAssignment;
    @Nullable
    private final ClusterResourceHolder primer;

    EndpointResourceHolder(ClusterLoadAssignment clusterLoadAssignment) {
        this.clusterLoadAssignment = clusterLoadAssignment;
        primer = null;
    }

    EndpointResourceHolder(ClusterResourceHolder primer, ClusterLoadAssignment clusterLoadAssignment) {
        this.primer = primer;
        this.clusterLoadAssignment = clusterLoadAssignment;
    }

    @Override
    public XdsType type() {
        return XdsType.ENDPOINT;
    }

    @Override
    public ClusterLoadAssignment resource() {
        return clusterLoadAssignment;
    }

    @Override
    public String name() {
        return clusterLoadAssignment.getClusterName();
    }

    @Override
    public EndpointResourceHolder withPrimer(@Nullable ResourceHolder primer) {
        if (primer == null) {
            return this;
        }
        checkArgument(primer instanceof ClusterResourceHolder);
        return new EndpointResourceHolder((ClusterResourceHolder) primer, clusterLoadAssignment);
    }

    @Override
    @Nullable
    public ClusterResourceHolder primer() {
        return primer;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final EndpointResourceHolder holder = (EndpointResourceHolder) object;
        return Objects.equal(clusterLoadAssignment, holder.clusterLoadAssignment) &&
               Objects.equal(primer, holder.primer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterLoadAssignment, primer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterLoadAssignment", clusterLoadAssignment)
                          .add("primer", primer)
                          .toString();
    }
}
