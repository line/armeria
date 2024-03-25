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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A snapshot of a {@link ClusterLoadAssignment} resource.
 */
@UnstableApi
public final class EndpointSnapshot implements Snapshot<EndpointXdsResource> {
    private final EndpointXdsResource endpoint;

    EndpointSnapshot(EndpointXdsResource endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public EndpointXdsResource xdsResource() {
        return endpoint;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final EndpointSnapshot that = (EndpointSnapshot) object;
        return Objects.equal(endpoint, that.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(endpoint);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpoint", endpoint)
                          .toString();
    }
}
