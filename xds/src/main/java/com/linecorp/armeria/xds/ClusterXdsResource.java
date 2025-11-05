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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

/**
 * A resource object for a {@link Cluster}.
 */
@UnstableApi
public final class ClusterXdsResource extends AbstractXdsResource {

    private final Cluster cluster;
    @Nullable
    UpstreamTlsContext upstreamTlsContext;

    ClusterXdsResource(Cluster cluster, String version, long revision) {
        super(version, revision);
        XdsValidatorIndex.of().assertValid(cluster);
        this.cluster = cluster;
        upstreamTlsContext = upstreamTlsContext(cluster);
    }

    @Nullable
    private static UpstreamTlsContext upstreamTlsContext(Cluster cluster) {
        if (cluster.hasTransportSocket()) {
            final String transportSocketName = cluster.getTransportSocket().getName();
            checkArgument("envoy.transport_sockets.tls".equals(transportSocketName),
                          "Unexpected tls transport socket name '%s'", transportSocketName);
            return XdsValidatorIndex.of().unpack(cluster.getTransportSocket().getTypedConfig(),
                                                 UpstreamTlsContext.class);
        }
        return null;
    }

    /**
     * The upstream TLS context extracted from {@link Cluster#getTransportSocket()}.
     */
    @Nullable
    @UnstableApi
    public UpstreamTlsContext upstreamTlsContext() {
        return upstreamTlsContext;
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
}
