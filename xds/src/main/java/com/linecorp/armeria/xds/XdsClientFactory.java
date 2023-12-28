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

import java.util.function.Consumer;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

final class XdsClientFactory implements SafeCloseable, ResourceWatcher<ClusterResourceHolder> {

    private final EndpointGroup endpointGroup;
    private final Consumer<GrpcClientBuilder> listener;
    private final SafeCloseable clusterWatcher;

    XdsClientFactory(XdsBootstrapImpl xdsBootstrap, String clusterName,
                     Consumer<GrpcClientBuilder> listener) {
        endpointGroup = XdsEndpointGroup.of(xdsBootstrap, XdsType.CLUSTER, clusterName, false);
        clusterWatcher = xdsBootstrap.addClusterWatcher(clusterName, this);
        this.listener = listener;
    }

    @Override
    public void close() {
        clusterWatcher.close();
        endpointGroup.close();
    }

    @Override
    public void onChanged(ClusterResourceHolder update) {
        final Cluster cluster = update.data();
        UpstreamTlsContext tlsContext = null;
        if (cluster.hasTransportSocket()) {
            final String transportSocketName = cluster.getTransportSocket().getName();
            assert "envoy.transport_sockets.tls".equals(transportSocketName);
            try {
                tlsContext = cluster.getTransportSocket().getTypedConfig().unpack(
                        UpstreamTlsContext.class);
            } catch (Exception e) {
                throw new RuntimeException("Error unpacking tls context", e);
            }
        }
        final SessionProtocol sessionProtocol =
                tlsContext != null ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
        final GrpcClientBuilder builder = GrpcClients.builder(sessionProtocol, endpointGroup);
        listener.accept(builder);
    }
}
