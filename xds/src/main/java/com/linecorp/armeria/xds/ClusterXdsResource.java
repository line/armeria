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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

/**
 * A resource object for a {@link Cluster}.
 */
@UnstableApi
public final class ClusterXdsResource extends AbstractXdsResource {

    private final Cluster cluster;

    ClusterXdsResource(Cluster cluster) {
        this(cluster, "");
    }

    ClusterXdsResource(Cluster cluster, String version) {
        this(cluster, version, 0);
    }

    private ClusterXdsResource(Cluster cluster, String version, long revision) {
        super(version, revision);
        this.cluster = cluster;
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
    ClusterXdsResource withRevision(long revision) {
        if (revision == revision()) {
            return this;
        }
        return new ClusterXdsResource(cluster, version(), revision);
    }
}
