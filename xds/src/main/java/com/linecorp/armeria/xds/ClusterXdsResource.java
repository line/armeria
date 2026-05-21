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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;

/**
 * A resource object for a {@link Cluster}.
 */
@UnstableApi
public final class ClusterXdsResource extends AbstractXdsResource {

    private final Cluster cluster;
    @Nullable
    private final HttpProtocolOptions httpProtocolOptions;

    ClusterXdsResource(Cluster cluster, String version,
                       @Nullable HttpProtocolOptions httpProtocolOptions) {
        this(cluster, version, 0, httpProtocolOptions);
    }

    private ClusterXdsResource(Cluster cluster, String version, long revision,
                               @Nullable HttpProtocolOptions httpProtocolOptions) {
        super(version, revision);
        this.cluster = cluster;
        this.httpProtocolOptions = httpProtocolOptions;
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

    /**
     * Returns the parsed {@link HttpProtocolOptions} from the cluster's
     * {@code typed_extension_protocol_options}, or {@code null} if not present.
     */
    @Nullable
    public HttpProtocolOptions httpProtocolOptions() {
        return httpProtocolOptions;
    }

    @Override
    ClusterXdsResource withRevision(long revision) {
        if (revision == revision()) {
            return this;
        }
        return new ClusterXdsResource(cluster, version(), revision, httpProtocolOptions);
    }
}
