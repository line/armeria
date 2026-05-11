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

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

final class UpstreamTlsTransportSocketFactory implements TransportSocketFactory {

    static final UpstreamTlsTransportSocketFactory INSTANCE = new UpstreamTlsTransportSocketFactory();
    private static final String NAME = "envoy.transport_sockets.tls";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

    private UpstreamTlsTransportSocketFactory() {}

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
        final UpstreamTlsContext tlsContext = context.extensionRegistry().unpack(
                transportSocket.getTypedConfig(), UpstreamTlsContext.class);
        final CommonTlsContext commonTlsContext = tlsContext.getCommonTlsContext();

        final SnapshotStream<Optional<CertificateValidationContextSnapshot>> validationStream;

        if (commonTlsContext.hasValidationContext()) {
            final Secret secret = Secret.newBuilder()
                                        .setValidationContext(commonTlsContext.getValidationContext())
                                        .build();
            final SecretStream secretStream = new SecretStream(secret, context);
            validationStream = secretStream
                    .switchMapEager(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
            final SdsSecretConfig sdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            validationStream = secretStream
                    .switchMapEager(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasCombinedValidationContext()) {
            final CombinedCertificateValidationContext combined =
                    commonTlsContext.getCombinedValidationContext();
            final SdsSecretConfig sdsConfig = combined.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            validationStream = secretStream.switchMapEager(resource -> new CertificateValidationContextStream(
                                                   context, resource, combined.getDefaultValidationContext()))
                                           .map(Optional::of);
        } else {
            validationStream = SnapshotStream.empty();
        }

        final SnapshotStream<Optional<TlsCertificateSnapshot>> tlsCertStream;
        if (!commonTlsContext.getTlsCertificatesList().isEmpty()) {
            final TlsCertificate tlsCertificate = commonTlsContext.getTlsCertificatesList().get(0);
            final Secret secret = Secret.newBuilder().setTlsCertificate(tlsCertificate).build();
            final SecretStream secretStream = new SecretStream(secret, context);
            tlsCertStream = secretStream.switchMapEager(resource -> new TlsCertificateStream(context, resource))
                                        .map(Optional::of);
        } else if (!commonTlsContext.getTlsCertificateSdsSecretConfigsList().isEmpty()) {
            final SdsSecretConfig sdsConfig =
                    commonTlsContext.getTlsCertificateSdsSecretConfigsList().get(0);
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            tlsCertStream = secretStream.switchMapEager(resource -> new TlsCertificateStream(context, resource))
                                        .map(Optional::of);
        } else {
            // static
            tlsCertStream = SnapshotStream.empty();
        }

        return SnapshotStream.combineLatest(tlsCertStream, validationStream, (cert, validation) -> {
            return new TransportSocketSnapshot(transportSocket, tlsContext, cert, validation);
        });
    }
}
