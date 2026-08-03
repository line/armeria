/*
 * Copyright 2026 LY Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.Metadata;

final class DefaultRouteCluster implements RouteCluster {

    private final ClusterSnapshot clusterSnapshot;
    private final Metadata metadataMatch;
    private final HttpClient httpClient;
    private final RpcClient rpcClient;

    DefaultRouteCluster(ClusterSnapshot clusterSnapshot, Metadata metadataMatch,
                        @Nullable ClientDecoration retryDecoration,
                        ClientDecoration downstreamDecoration,
                        ClientDecoration upstreamDecoration) {
        this.clusterSnapshot = requireNonNull(clusterSnapshot, "clusterSnapshot");
        this.metadataMatch = requireNonNull(metadataMatch, "metadataMatch");
        httpClient = FilterUtil.buildHttpClient(retryDecoration, downstreamDecoration, upstreamDecoration);
        rpcClient = FilterUtil.buildRpcClient(retryDecoration, downstreamDecoration, upstreamDecoration);
    }

    @Override
    public ClusterSnapshot clusterSnapshot() {
        return clusterSnapshot;
    }

    @Override
    public Metadata metadataMatch() {
        return metadataMatch;
    }

    @Override
    public HttpClient httpClient() {
        return httpClient;
    }

    @Override
    public RpcClient rpcClient() {
        return rpcClient;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultRouteCluster that = (DefaultRouteCluster) o;
        return Objects.equals(clusterSnapshot, that.clusterSnapshot) &&
               Objects.equals(metadataMatch, that.metadataMatch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterSnapshot, metadataMatch);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterSnapshot", clusterSnapshot)
                          .add("metadataMatch", metadataMatch)
                          .toString();
    }
}
