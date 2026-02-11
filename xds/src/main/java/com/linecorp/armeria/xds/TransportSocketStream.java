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

import java.util.Optional;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

final class TransportSocketStream extends RefCountedStream<TransportSocketSnapshot> {

    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final TransportSocket transportSocket;

    TransportSocketStream(SubscriptionContext context, @Nullable ConfigSource configSource,
                          TransportSocket transportSocket) {
        this.context = context;
        this.configSource = configSource;
        this.transportSocket = transportSocket;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<TransportSocketSnapshot> watcher) {
        if (!"envoy.transport_sockets.tls".equals(transportSocket.getName())) {
            return SnapshotStream.just(new TransportSocketSnapshot(transportSocket))
                                 .subscribe(watcher);
        }
        final UpstreamTlsContext tlsContext = XdsValidatorIndexRegistry.unpack(transportSocket.getTypedConfig(),
                                                                               UpstreamTlsContext.class);
        final CommonTlsContext commonTlsContext = tlsContext.getCommonTlsContext();

        final SnapshotStream<Optional<CertificateValidationContextSnapshot>> validationStream;

        if (commonTlsContext.hasValidationContext()) {
            final Secret secret = Secret.newBuilder()
                                        .setValidationContext(commonTlsContext.getValidationContext())
                                        .build();
            final SecretStream secretStream = new SecretStream(secret, context);
            validationStream = secretStream
                    .switchMap(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
            final SdsSecretConfig sdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            validationStream = secretStream
                    .switchMap(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasCombinedValidationContext()) {
            final CombinedCertificateValidationContext combined =
                    commonTlsContext.getCombinedValidationContext();
            final SdsSecretConfig sdsConfig = combined.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            validationStream = secretStream.switchMap(resource -> new CertificateValidationContextStream(
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
            tlsCertStream = secretStream.switchMap(resource -> new TlsCertificateStream(context, resource))
                                        .map(Optional::of);
        } else if (!commonTlsContext.getTlsCertificateSdsSecretConfigsList().isEmpty()) {
            final SdsSecretConfig sdsConfig =
                    commonTlsContext.getTlsCertificateSdsSecretConfigsList().get(0);
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            tlsCertStream = secretStream.switchMap(resource -> new TlsCertificateStream(context, resource))
                                        .map(Optional::of);
        } else {
            // static
            tlsCertStream = SnapshotStream.empty();
        }

        final SnapshotStream<TransportSocketSnapshot> stream =
                SnapshotStream.combineLatest(tlsCertStream, validationStream, (cert, validation) -> {
                    return new TransportSocketSnapshot(transportSocket, cert, validation);
                });
        return stream.subscribe(watcher);
    }
}
