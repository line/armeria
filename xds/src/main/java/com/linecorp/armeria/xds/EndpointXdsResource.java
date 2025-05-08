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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A resource object for a {@link ClusterLoadAssignment}.
 */
@UnstableApi
public final class EndpointXdsResource extends XdsResourceWithPrimer<EndpointXdsResource> {

    private final ClusterLoadAssignment clusterLoadAssignment;
    @Nullable
    private final XdsResource primer;

    EndpointXdsResource(ClusterLoadAssignment clusterLoadAssignment) {
        this.clusterLoadAssignment = clusterLoadAssignment;
        primer = null;
    }

    EndpointXdsResource(ClusterLoadAssignment clusterLoadAssignment, XdsResource primer) {
        this.clusterLoadAssignment = clusterLoadAssignment;
        this.primer = primer;
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
    EndpointXdsResource withPrimer(@Nullable XdsResource primer) {
        if (primer == null) {
            return this;
        }
        return new EndpointXdsResource(clusterLoadAssignment, primer);
    }

    @Override
    @Nullable
    XdsResource primer() {
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
        final EndpointXdsResource resource = (EndpointXdsResource) object;
        return Objects.equal(clusterLoadAssignment, resource.clusterLoadAssignment);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterLoadAssignment);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterLoadAssignment", clusterLoadAssignment)
                          .toString();
    }
}
