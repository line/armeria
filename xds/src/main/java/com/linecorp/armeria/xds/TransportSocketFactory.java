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
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;

interface TransportSocketFactory extends XdsExtensionFactory {

    SnapshotStream<TransportSocketSnapshot> create(SubscriptionContext context,
                                                   @Nullable ConfigSource configSource,
                                                   TransportSocket transportSocket);

    static SnapshotStream<Optional<CertificateValidationContextSnapshot>> resolveValidationContext(
            CommonTlsContext commonTlsContext, @Nullable ConfigSource configSource,
            SubscriptionContext context) {
        if (commonTlsContext.hasValidationContext()) {
            final Secret secret = Secret.newBuilder()
                                        .setValidationContext(commonTlsContext.getValidationContext())
                                        .build();
            final SecretStream secretStream = new SecretStream(secret, context);
            return secretStream
                    .switchMapEager(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
            final SdsSecretConfig sdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            return secretStream
                    .switchMapEager(resource -> new CertificateValidationContextStream(context, resource))
                    .map(Optional::of);
        } else if (commonTlsContext.hasCombinedValidationContext()) {
            final CombinedCertificateValidationContext combined =
                    commonTlsContext.getCombinedValidationContext();
            final SdsSecretConfig sdsConfig = combined.getValidationContextSdsSecretConfig();
            final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
            return secretStream.switchMapEager(resource -> new CertificateValidationContextStream(
                                                   context, resource, combined.getDefaultValidationContext()))
                               .map(Optional::of);
        }
        return SnapshotStream.empty();
    }

    static SnapshotStream<List<TlsCertificateSnapshot>> resolveTlsCertificates(
            CommonTlsContext commonTlsContext, @Nullable ConfigSource configSource,
            SubscriptionContext context) {
        if (!commonTlsContext.getTlsCertificatesList().isEmpty()) {
            final ImmutableList.Builder<SnapshotStream<TlsCertificateSnapshot>> streams =
                    ImmutableList.builder();
            for (TlsCertificate tlsCertificate : commonTlsContext.getTlsCertificatesList()) {
                final Secret secret = Secret.newBuilder().setTlsCertificate(tlsCertificate).build();
                final SecretStream secretStream = new SecretStream(secret, context);
                streams.add(secretStream.switchMapEager(
                        resource -> new TlsCertificateStream(context, resource)));
            }
            return SnapshotStream.combineNLatest(streams.build());
        } else if (!commonTlsContext.getTlsCertificateSdsSecretConfigsList().isEmpty()) {
            final ImmutableList.Builder<SnapshotStream<TlsCertificateSnapshot>> streams =
                    ImmutableList.builder();
            for (SdsSecretConfig sdsConfig
                    : commonTlsContext.getTlsCertificateSdsSecretConfigsList()) {
                final SecretStream secretStream = new SecretStream(sdsConfig, configSource, context);
                streams.add(secretStream.switchMapEager(
                        resource -> new TlsCertificateStream(context, resource)));
            }
            return SnapshotStream.combineNLatest(streams.build());
        }
        return SnapshotStream.just(ImmutableList.of());
    }
}
