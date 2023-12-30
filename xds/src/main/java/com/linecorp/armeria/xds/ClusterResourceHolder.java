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

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

/**
 * A holder object for a {@link Cluster}.
 */
public final class ClusterResourceHolder implements ResourceHolder<Cluster> {

    private final Cluster cluster;

    ClusterResourceHolder(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public XdsType type() {
        return XdsType.CLUSTER;
    }

    @Override
    public Cluster data() {
        return cluster;
    }

    @Override
    public String name() {
        return cluster.getName();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("cluster", cluster)
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
        final ClusterResourceHolder that = (ClusterResourceHolder) object;
        return Objects.equal(cluster, that.cluster);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(cluster);
    }
}
