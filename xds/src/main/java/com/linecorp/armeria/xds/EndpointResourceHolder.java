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

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A holder object for a {@link ClusterLoadAssignment}.
 */
public final class EndpointResourceHolder implements ResourceHolder<ClusterLoadAssignment> {

    private final ClusterLoadAssignment clusterLoadAssignment;

    EndpointResourceHolder(ClusterLoadAssignment clusterLoadAssignment) {
        this.clusterLoadAssignment = clusterLoadAssignment;
    }

    @Override
    public XdsType type() {
        return XdsType.ENDPOINT;
    }

    @Override
    public ClusterLoadAssignment data() {
        return clusterLoadAssignment;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterLoadAssignment", clusterLoadAssignment)
                          .toString();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final EndpointResourceHolder that = (EndpointResourceHolder) object;
        return Objects.equal(clusterLoadAssignment, that.clusterLoadAssignment);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterLoadAssignment);
    }
}
