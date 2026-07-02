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

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.ClientTlsSpecBuilder;
import com.linecorp.armeria.common.AbstractTlsSpecBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.ServerTlsSpec;
import com.linecorp.armeria.server.ServerTlsSpecBuilder;

import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.netty.handler.ssl.ClientAuth;

/**
 * A snapshot of a {@link TransportSocket} resource with its associated TLS configuration.
 * This snapshot includes optional {@link TlsCertificateSnapshot} and
 * {@link CertificateValidationContextSnapshot} for TLS connections.
 */
@UnstableApi
public final class TransportSocketSnapshot implements Snapshot<TransportSocket> {

    private static final Logger logger = LoggerFactory.getLogger(TransportSocketSnapshot.class);

    private final TransportSocket transportSocket;
    private final List<TlsCertificateSnapshot> tlsCertificates;
    @Nullable
    private final CertificateValidationContextSnapshot validationContext;
    @Nullable
    private final ClientTlsSpec clientTlsSpec;
    @Nullable
    private final ServerTlsSpecSelector serverTlsSpecSelector;

    TransportSocketSnapshot(TransportSocket transportSocket) {
        this.transportSocket = transportSocket;
        tlsCertificates = ImmutableList.of();
        validationContext = null;
        clientTlsSpec = null;
        serverTlsSpecSelector = null;
    }

    TransportSocketSnapshot(TransportSocket transportSocket,
                            @Nullable UpstreamTlsContext upstreamTlsContext,
                            List<TlsCertificateSnapshot> tlsCertificates,
                            Optional<CertificateValidationContextSnapshot> validationContext) {
        this.transportSocket = transportSocket;
        this.tlsCertificates = ImmutableList.copyOf(tlsCertificates);
        this.validationContext = validationContext.orElse(null);
        final TlsCertificateSnapshot firstCert = tlsCertificates.isEmpty() ? null
                                                                           : tlsCertificates.get(0);
        clientTlsSpec = buildClientTlsSpec(upstreamTlsContext, firstCert, this.validationContext);
        serverTlsSpecSelector = null;
    }

    TransportSocketSnapshot(TransportSocket transportSocket,
                            DownstreamTlsContext downstreamTlsContext,
                            List<TlsCertificateSnapshot> tlsCertificates,
                            Optional<CertificateValidationContextSnapshot> validationContext) {
        this.transportSocket = transportSocket;
        this.tlsCertificates = ImmutableList.copyOf(tlsCertificates);
        this.validationContext = validationContext.orElse(null);
        clientTlsSpec = null;

        final List<ServerTlsSpec> specs =
                tlsCertificates.stream()
                               .map(cert -> buildServerTlsSpec(downstreamTlsContext, cert,
                                                               this.validationContext))
                               .collect(ImmutableList.toImmutableList());
        serverTlsSpecSelector = new ServerTlsSpecSelector(specs, this.tlsCertificates);
    }

    @Override
    public TransportSocket xdsResource() {
        return transportSocket;
    }

    /**
     * Returns the first {@link TlsCertificateSnapshot} containing the certificate and private key
     * for this transport socket, or {@code null} if not configured.
     */
    public @Nullable TlsCertificateSnapshot tlsCertificate() {
        return tlsCertificates.isEmpty() ? null : tlsCertificates.get(0);
    }

    /**
     * Returns all {@link TlsCertificateSnapshot}s configured for this transport socket.
     * Multiple certificates may be configured for downstream (server-side) TLS to support SNI.
     */
    public List<TlsCertificateSnapshot> tlsCertificates() {
        return tlsCertificates;
    }

    /**
     * Returns the {@link CertificateValidationContextSnapshot} containing the trusted CA certificates
     * for validating peer certificates, or {@code null} if not configured.
     */
    public @Nullable CertificateValidationContextSnapshot validationContext() {
        return validationContext;
    }

    /**
     * Returns the {@link ClientTlsSpec} resolved for this transport socket, or {@code null}
     * if this transport socket does not configure upstream TLS.
     */
    public @Nullable ClientTlsSpec clientTlsSpec() {
        return clientTlsSpec;
    }

    /**
     * Selects the best-matching {@link ServerTlsSpec} for the given connection using
     * SNI-based certificate selection, following Envoy's
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/security/ssl#arch-overview-ssl-cert-select">
     * certificate selection</a> algorithm.
     *
     * <p>Selection order:
     * <ol>
     *   <li>Exact SNI match against certificate DNS SANs (or CN if no SANs)</li>
     *   <li>Wildcard match (one level only, e.g. {@code *.example.com})</li>
     *   <li>Fallback to the first certificate</li>
     * </ol>
     *
     * @return the selected {@link ServerTlsSpec}, or {@code null} if this transport socket
     *         does not configure downstream TLS
     */
    public @Nullable ServerTlsSpec serverTlsSpec(ConnectionContext ctx) {
        requireNonNull(ctx, "ctx");
        return serverTlsSpecSelector != null ? serverTlsSpecSelector.select(ctx) : null;
    }

    private static ClientTlsSpec buildClientTlsSpec(
            @Nullable UpstreamTlsContext upstreamTlsContext,
            @Nullable TlsCertificateSnapshot tlsCertificate,
            @Nullable CertificateValidationContextSnapshot validationContext) {
        final ClientTlsSpecBuilder specBuilder = ClientTlsSpec.builder();
        final boolean autoSniSanValidation = upstreamTlsContext != null &&
                                             upstreamTlsContext.getAutoSniSanValidation();
        if (!autoSniSanValidation) {
            specBuilder.endpointIdentificationAlgorithm("");
        }
        if (upstreamTlsContext != null) {
            final List<String> alpn = upstreamTlsContext.getCommonTlsContext().getAlpnProtocolsList();
            if (!alpn.isEmpty()) {
                specBuilder.alpnProtocols(alpn);
            }
        }
        applyCommonTlsConfig(specBuilder, tlsCertificate, validationContext, autoSniSanValidation);
        return specBuilder.build();
    }

    private static <B extends AbstractTlsSpecBuilder<B, ?>> void applyCommonTlsConfig(
            B builder,
            @Nullable TlsCertificateSnapshot tlsCertificate,
            @Nullable CertificateValidationContextSnapshot validationContext,
            boolean autoSniSanValidation) {
        if (tlsCertificate != null) {
            final TlsKeyPair tlsKeyPair = tlsCertificate.tlsKeyPair();
            if (tlsKeyPair != null) {
                builder.tlsKeyPair(tlsKeyPair);
            }
        }
        final ImmutableList.Builder<TlsPeerVerifierFactory> verifiersBuilder = ImmutableList.builder();
        if (validationContext != null) {
            final boolean systemRootCerts = validationContext.xdsResource().hasSystemRootCerts();
            final List<X509Certificate> trustedCa = validationContext.trustedCa();
            if (trustedCa != null) {
                builder.trustedCertificates(trustedCa);
            } else if (systemRootCerts) {
                // use java default root CAs
            } else {
                if (autoSniSanValidation) {
                    throw new IllegalArgumentException(
                            "'auto_sni_san_validation' was configured without configuring a trusted CA");
                }
                logger.warn("TLS peer verification is disabled: validation_context has no " +
                            "trusted_ca and system_root_certs is unset. " +
                            "Set 'system_root_certs: \\{}' or provide trusted_ca.");
                verifiersBuilder.add(TlsPeerVerifierFactory.noVerify());
            }
            final List<TlsPeerVerifierFactory> verifierFactories = validationContext.peerVerifierFactories();
            if (!verifierFactories.isEmpty()) {
                verifiersBuilder.addAll(verifierFactories);
            }
        } else {
            logger.warn("TLS peer verification is disabled: no validation_context configured. " +
                        "Configure a validation_context with trusted_ca or system_root_certs.");
            verifiersBuilder.add(TlsPeerVerifierFactory.noVerify());
        }
        final List<TlsPeerVerifierFactory> verifierFactories = verifiersBuilder.build();
        if (!verifierFactories.isEmpty()) {
            builder.verifierFactories(verifierFactories);
        }
    }

    private static ServerTlsSpec buildServerTlsSpec(
            DownstreamTlsContext downstreamTlsContext,
            TlsCertificateSnapshot tlsCertificate,
            @Nullable CertificateValidationContextSnapshot validationContext) {
        if (tlsCertificate.tlsKeyPair() == null) {
            throw new IllegalArgumentException(
                    "Server TlsCertificateSnapshot must have a TlsKeyPair: " + tlsCertificate);
        }
        final ServerTlsSpecBuilder builder = ServerTlsSpec.builder();
        applyCommonTlsConfig(builder, tlsCertificate, validationContext, false);
        if (DownstreamTlsTransportSocketFactory.requireClientCertificate(downstreamTlsContext)) {
            builder.clientAuth(ClientAuth.REQUIRE);
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final TransportSocketSnapshot that = (TransportSocketSnapshot) object;
        return Objects.equal(transportSocket, that.transportSocket) &&
               Objects.equal(tlsCertificates, that.tlsCertificates) &&
               Objects.equal(validationContext, that.validationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(transportSocket, tlsCertificates, validationContext);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("tlsCertificates", tlsCertificates)
                          .add("validationContext", validationContext)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("transportSocket", transportSocket)
                          .add("tlsCertificates",
                               tlsCertificates.stream().map(TlsCertificateSnapshot::toDebugString)
                                              .collect(ImmutableList.toImmutableList()))
                          .add("validationContext",
                               SnapshotUtil.debugString(validationContext,
                                                        CertificateValidationContextSnapshot::toDebugString))
                          .toString();
    }
}
