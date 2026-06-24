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

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;

/**
 * Server-side (downstream) TLS transport socket factory. Mirrors
 * {@link UpstreamTlsTransportSocketFactory} but unpacks {@link DownstreamTlsContext}.
 */
final class DownstreamTlsTransportSocketFactory implements TransportSocketFactory {

    private static final String NAME = "envoy.transport_sockets.downstream_tls";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);
    static final DownstreamTlsTransportSocketFactory INSTANCE =
            new DownstreamTlsTransportSocketFactory();

    private DownstreamTlsTransportSocketFactory() {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return TYPE_URLS;
    }

    @Override
    public SnapshotStream<TransportSocketSnapshot> create(
            SubscriptionContext context, @Nullable ConfigSource configSource,
            TransportSocket transportSocket) {
        if (!transportSocket.hasTypedConfig()) {
            return SnapshotStream.just(new TransportSocketSnapshot(TransportSocket.getDefaultInstance()));
        }
        final DownstreamTlsContext tlsContext = context.extensionRegistry().unpack(
                transportSocket.getTypedConfig(), DownstreamTlsContext.class);
        final CommonTlsContext commonTlsContext = tlsContext.getCommonTlsContext();
        final SnapshotStream<Optional<CertificateValidationContextSnapshot>> validationStream =
                TransportSocketFactory.resolveValidationContext(commonTlsContext, configSource, context);
        final SnapshotStream<List<TlsCertificateSnapshot>> tlsCertStream =
                TransportSocketFactory.resolveTlsCertificates(commonTlsContext, configSource, context);

        return SnapshotStream.combineLatest(tlsCertStream, validationStream, (certs, validation) -> {
            return new TransportSocketSnapshot(transportSocket, tlsContext, certs, validation);
        });
    }
}
