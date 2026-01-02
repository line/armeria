/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServerTlsSpec;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.ReferenceCountUtil;

/**
 * Utilities for configuring {@link SslContextBuilder}.
 */
public final class SslContextUtil {

    private static final Logger logger = LoggerFactory.getLogger(SslContextUtil.class);

    public static final Set<String> DEFAULT_ALPN_PROTOCOLS =
            ImmutableSet.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
    public static final Set<String> DEFAULT_HTTP1_ALPN_PROTOCOLS =
            ImmutableSet.of(ApplicationProtocolNames.HTTP_1_1);
    public static final Consumer<? super SslContextBuilder> DEFAULT_NOOP_CUSTOMIZER = ignored -> {};

    private static final ApplicationProtocolConfig ALPN_CONFIG = new ApplicationProtocolConfig(
            Protocol.ALPN,
            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
            SelectorFailureBehavior.NO_ADVERTISE,
            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1);

    // OpenSSL's default enabled TLSv1.3 ciphers as documented at https://wiki.openssl.org/index.php/TLS1.3
    private static final List<String> TLS_V13_CIPHERS = ImmutableList.of("TLS_AES_256_GCM_SHA384",
                                                                         "TLS_CHACHA20_POLY1305_SHA256",
                                                                         "TLS_AES_128_GCM_SHA256");

    public static final Set<String> DEFAULT_CIPHERS = ImmutableSet.<String>builder()
                                                                  .addAll(TLS_V13_CIPHERS)
                                                                  .addAll(Http2SecurityUtil.CIPHERS)
                                                                  .build();

    public static final List<String> DEFAULT_PROTOCOLS = ImmutableList.of("TLSv1.3", "TLSv1.2");

    private static final String ESSENTIAL_HTTP2_CIPHER_SUITE = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
    private static final String MISSING_ESSENTIAL_CIPHER_SUITE_MESSAGE =
            "Attempted to configure TLS without the " + ESSENTIAL_HTTP2_CIPHER_SUITE +
            " cipher suite enabled. It must be enabled for proper HTTP/2 support.";
    private static final ImmutableSet<String> tlsVersion23 = ImmutableSet.of("TLSv1.3", "TLSv1.2");
    private static final ImmutableSet<String> tlsVersion2 = ImmutableSet.of("TLSv1.2");

    private static boolean warnedUnsupportedProtocols;
    private static boolean warnedMissingEssentialCipherSuite;
    private static boolean warnedBadCipherSuite;

    public static SslContext toSslContext(ClientTlsSpec clientTlsSpec, boolean allowUnsafeCiphers) {
        return MinifiedBouncyCastleProvider.call(() -> {
            SslContext sslContext = null;
            try {
                sslContext = toSslContext0(clientTlsSpec);
                validateSslContext(allowUnsafeCiphers, sslContext);
            } catch (Exception e) {
                ReferenceCountUtil.release(sslContext);
                return Exceptions.throwUnsafely(e);
            }
            return sslContext;
        });
    }

    public static SslContext toSslContext(ServerTlsSpec serverTlsSpec, boolean allowUnsafeCiphers) {
        return MinifiedBouncyCastleProvider.call(() -> {
            SslContext sslContext = null;
            try {
                sslContext = toSslContext0(serverTlsSpec);
                validateSslContext(allowUnsafeCiphers, sslContext);
            } catch (Exception e) {
                ReferenceCountUtil.release(sslContext);
                return Exceptions.throwUnsafely(e);
            }
            return sslContext;
        });
    }

    private static SslContext toSslContext0(ClientTlsSpec clientTlsSpec) throws Exception {
        final SslContextBuilder builder = SslContextBuilder.forClient();

        final TlsKeyPair keyPair = clientTlsSpec.tlsKeyPair();
        if (keyPair != null) {
            builder.keyManager(keyPair.privateKey(), keyPair.certificateChain());
        }
        builder.endpointIdentificationAlgorithm("HTTPS");

        applyCommonConfigs(clientTlsSpec, builder);

        return builder.build();
    }

    private static SslContext toSslContext0(ServerTlsSpec serverTlsSpec) throws Exception {
        final SslContextBuilder contextBuilder;
        final TlsKeyPair keyPair = serverTlsSpec.tlsKeyPair();
        if (keyPair != null) {
            contextBuilder = SslContextBuilder.forServer(keyPair.privateKey(), keyPair.certificateChain());
        } else {
            final KeyManagerFactory keyManagerFactory = serverTlsSpec.keyManagerFactory();
            assert keyManagerFactory != null;
            contextBuilder = SslContextBuilder.forServer(keyManagerFactory);
        }
        contextBuilder.clientAuth(ClientAuth.valueOf(serverTlsSpec.clientAuth()));
        applyCommonConfigs(serverTlsSpec, contextBuilder);
        return contextBuilder.build();
    }

    private static List<String> filterProtocols(Collection<String> protocols, SslProvider provider) {
        final Set<String> supportedProtocols = supportedTlsVersions(provider);
        final List<String> filtered = protocols.stream()
                                               .filter(supportedProtocols::contains)
                                               .collect(toImmutableList());
        if (filtered.isEmpty()) {
            throw new IllegalStateException(provider + " supports none of " + protocols);
        }

        if (!warnedUnsupportedProtocols && protocols.size() != filtered.size()) {
            warnedUnsupportedProtocols = true;
            if (logger.isDebugEnabled()) {
                final List<String> missingProtocols = protocols.stream()
                                                               .filter(p -> !filtered.contains(p))
                                                               .collect(toImmutableList());
                logger.debug("{} does not support: {}", provider, missingProtocols);
            }
        }
        return filtered;
    }

    private static void validateSslContext(boolean tlsAllowUnsafeCiphers, SslContext sslContext) {
        final Set<String> ciphers = ImmutableSet.copyOf(sslContext.cipherSuites());
        checkState(!ciphers.isEmpty(),
                   "SSLContext has no cipher suites enabled. " +
                   "You must specify at least one cipher suite.");
        if (sslContext.applicationProtocolNegotiator().protocols().contains(ApplicationProtocolNames.HTTP_2)) {
            validateHttp2Ciphers(ciphers, tlsAllowUnsafeCiphers);
        }
    }

    public static void checkVersionsSupported(Set<String> protocols, SslProvider provider) {
        checkArgument(!protocols.isEmpty(), "protocols cannot be empty");
        final Set<String> supportedTlsVersions = supportedTlsVersions(provider);
        for (String protocol : protocols) {
            checkArgument(supportedTlsVersions.contains(protocol),
                          "Unsupported TLS protocol: %s for %s", protocol, provider);
        }
    }

    public static Set<String> supportedTlsVersions(SslProvider provider) {
        if (SslProvider.isTlsv13Supported(provider)) {
            return tlsVersion23;
        } else {
            return tlsVersion2;
        }
    }

    static void validateHttp2Ciphers(Set<String> ciphers, boolean tlsAllowUnsafeCiphers) {
        if (!ciphers.contains(ESSENTIAL_HTTP2_CIPHER_SUITE)) {
            if (tlsAllowUnsafeCiphers) {
                if (!warnedMissingEssentialCipherSuite) {
                    warnedMissingEssentialCipherSuite = true;
                    logger.warn(MISSING_ESSENTIAL_CIPHER_SUITE_MESSAGE);
                }
            } else {
                throw new IllegalStateException(MISSING_ESSENTIAL_CIPHER_SUITE_MESSAGE);
            }
        }

        for (String cipher : ciphers) {
            if (BAD_HTTP2_CIPHERS.contains(cipher)) {
                if (tlsAllowUnsafeCiphers) {
                    if (!warnedBadCipherSuite) {
                        warnedBadCipherSuite = true;
                        logger.warn(badCipherSuiteMessage(cipher));
                    }
                    break;
                } else {
                    throw new IllegalStateException(badCipherSuiteMessage(cipher));
                }
            }
        }
    }

    private static String badCipherSuiteMessage(String cipher) {
        return "Attempted to configure TLS with a bad cipher suite (" + cipher + "). " +
               "Do not use any cipher suites listed in " +
               "https://datatracker.ietf.org/doc/html/rfc7540#appendix-A";
    }

    private static void applyCommonConfigs(AbstractTlsSpec tlsSpec, SslContextBuilder builder)
            throws Exception {
        if (tlsSpec.verifierFactories().isEmpty()) {
            if (!tlsSpec.trustedCertificates().isEmpty()) {
                builder.trustManager(tlsSpec.trustedCertificates());
            }
        } else {
            final KeyStore ks = toKeyStore(tlsSpec.trustedCertificates());
            final X509ExtendedTrustManager delegate = defaultPkixTrustManager(ks);
            builder.trustManager(toTrustManager(tlsSpec, delegate));
        }

        final List<String> protocols = filterProtocols(tlsSpec.tlsVersions(),
                                                       tlsSpec.engineType().sslProvider());
        builder.protocols(protocols);
        builder.ciphers(tlsSpec.ciphers(), SupportedCipherSuiteFilter.INSTANCE);
        builder.sslProvider(tlsSpec.engineType().sslProvider());

        tlsSpec.tlsCustomizer().accept(builder);

        // configurations aren't configurable by users
        final ApplicationProtocolConfig alpnConfig = new ApplicationProtocolConfig(
                Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                SelectedListenerFailureBehavior.ACCEPT, tlsSpec.alpnProtocols());
        builder.applicationProtocolConfig(alpnConfig);
    }

    public static X509ExtendedTrustManager toTrustManager(AbstractTlsSpec tlsSpec,
                                                          X509ExtendedTrustManager delegate)
            throws Exception {
        return new VerifierBasedTrustManager(delegate, tlsSpec.verifierFactories(),
                                             tlsSpec.isServer());
    }

    private static X509ExtendedTrustManager defaultPkixTrustManager(@Nullable KeyStore ks) throws Exception {
        X509ExtendedTrustManager pkix = null;
        final TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(ks);
        for (TrustManager tm : trustManagerFactory.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager) {
                pkix = (X509ExtendedTrustManager) tm;
                break;
            }
        }
        if (pkix == null) {
            throw new IllegalStateException("No X.509 X509ExtendedTrustManager from TMF");
        }
        return pkix;
    }

    @Nullable
    private static KeyStore toKeyStore(List<X509Certificate> trustAnchors) throws Exception {
        if (trustAnchors.isEmpty()) {
            return null;
        }
        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (int i = 0; i != trustAnchors.size(); i++) {
            ks.setCertificateEntry(Integer.toString(i), trustAnchors.get(i));
        }
        return ks;
    }

    // https://datatracker.ietf.org/doc/html/rfc7540#appendix-A
    @VisibleForTesting
    static final Set<String> BAD_HTTP2_CIPHERS =
            ImmutableSet.of(
                    "TLS_NULL_WITH_NULL_NULL",
                    "TLS_RSA_WITH_NULL_MD5",
                    "TLS_RSA_WITH_NULL_SHA",
                    "TLS_RSA_EXPORT_WITH_RC4_40_MD5",
                    "TLS_RSA_WITH_RC4_128_MD5",
                    "TLS_RSA_WITH_RC4_128_SHA",
                    "TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5",
                    "TLS_RSA_WITH_IDEA_CBC_SHA",
                    "TLS_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_RSA_WITH_DES_CBC_SHA",
                    "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_DH_DSS_WITH_DES_CBC_SHA",
                    "TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_DH_RSA_WITH_DES_CBC_SHA",
                    "TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_DHE_DSS_WITH_DES_CBC_SHA",
                    "TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_DHE_RSA_WITH_DES_CBC_SHA",
                    "TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DH_anon_EXPORT_WITH_RC4_40_MD5",
                    "TLS_DH_anon_WITH_RC4_128_MD5",
                    "TLS_DH_anon_EXPORT_WITH_DES40_CBC_SHA",
                    "TLS_DH_anon_WITH_DES_CBC_SHA",
                    "TLS_DH_anon_WITH_3DES_EDE_CBC_SHA",
                    "TLS_KRB5_WITH_DES_CBC_SHA",
                    "TLS_KRB5_WITH_3DES_EDE_CBC_SHA",
                    "TLS_KRB5_WITH_RC4_128_SHA",
                    "TLS_KRB5_WITH_IDEA_CBC_SHA",
                    "TLS_KRB5_WITH_DES_CBC_MD5",
                    "TLS_KRB5_WITH_3DES_EDE_CBC_MD5",
                    "TLS_KRB5_WITH_RC4_128_MD5",
                    "TLS_KRB5_WITH_IDEA_CBC_MD5",
                    "TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA",
                    "TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA",
                    "TLS_KRB5_EXPORT_WITH_RC4_40_SHA",
                    "TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5",
                    "TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5",
                    "TLS_KRB5_EXPORT_WITH_RC4_40_MD5",
                    "TLS_PSK_WITH_NULL_SHA",
                    "TLS_DHE_PSK_WITH_NULL_SHA",
                    "TLS_RSA_PSK_WITH_NULL_SHA",
                    "TLS_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_DH_DSS_WITH_AES_128_CBC_SHA",
                    "TLS_DH_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
                    "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_DH_anon_WITH_AES_128_CBC_SHA",
                    "TLS_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_DH_DSS_WITH_AES_256_CBC_SHA",
                    "TLS_DH_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_DHE_DSS_WITH_AES_256_CBC_SHA",
                    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_DH_anon_WITH_AES_256_CBC_SHA",
                    "TLS_RSA_WITH_NULL_SHA256",
                    "TLS_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_RSA_WITH_AES_256_CBC_SHA256",
                    "TLS_DH_DSS_WITH_AES_128_CBC_SHA256",
                    "TLS_DH_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256",
                    "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA",
                    "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_DH_DSS_WITH_AES_256_CBC_SHA256",
                    "TLS_DH_RSA_WITH_AES_256_CBC_SHA256",
                    "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256",
                    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
                    "TLS_DH_anon_WITH_AES_128_CBC_SHA256",
                    "TLS_DH_anon_WITH_AES_256_CBC_SHA256",
                    "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA",
                    "TLS_PSK_WITH_RC4_128_SHA",
                    "TLS_PSK_WITH_3DES_EDE_CBC_SHA",
                    "TLS_PSK_WITH_AES_128_CBC_SHA",
                    "TLS_PSK_WITH_AES_256_CBC_SHA",
                    "TLS_DHE_PSK_WITH_RC4_128_SHA",
                    "TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA",
                    "TLS_DHE_PSK_WITH_AES_128_CBC_SHA",
                    "TLS_DHE_PSK_WITH_AES_256_CBC_SHA",
                    "TLS_RSA_PSK_WITH_RC4_128_SHA",
                    "TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA",
                    "TLS_RSA_PSK_WITH_AES_128_CBC_SHA",
                    "TLS_RSA_PSK_WITH_AES_256_CBC_SHA",
                    "TLS_RSA_WITH_SEED_CBC_SHA",
                    "TLS_DH_DSS_WITH_SEED_CBC_SHA",
                    "TLS_DH_RSA_WITH_SEED_CBC_SHA",
                    "TLS_DHE_DSS_WITH_SEED_CBC_SHA",
                    "TLS_DHE_RSA_WITH_SEED_CBC_SHA",
                    "TLS_DH_anon_WITH_SEED_CBC_SHA",
                    "TLS_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_DH_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_DH_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_DH_DSS_WITH_AES_128_GCM_SHA256",
                    "TLS_DH_DSS_WITH_AES_256_GCM_SHA384",
                    "TLS_DH_anon_WITH_AES_128_GCM_SHA256",
                    "TLS_DH_anon_WITH_AES_256_GCM_SHA384",
                    "TLS_PSK_WITH_AES_128_GCM_SHA256",
                    "TLS_PSK_WITH_AES_256_GCM_SHA384",
                    "TLS_RSA_PSK_WITH_AES_128_GCM_SHA256",
                    "TLS_RSA_PSK_WITH_AES_256_GCM_SHA384",
                    "TLS_PSK_WITH_AES_128_CBC_SHA256",
                    "TLS_PSK_WITH_AES_256_CBC_SHA384",
                    "TLS_PSK_WITH_NULL_SHA256",
                    "TLS_PSK_WITH_NULL_SHA384",
                    "TLS_DHE_PSK_WITH_AES_128_CBC_SHA256",
                    "TLS_DHE_PSK_WITH_AES_256_CBC_SHA384",
                    "TLS_DHE_PSK_WITH_NULL_SHA256",
                    "TLS_DHE_PSK_WITH_NULL_SHA384",
                    "TLS_RSA_PSK_WITH_AES_128_CBC_SHA256",
                    "TLS_RSA_PSK_WITH_AES_256_CBC_SHA384",
                    "TLS_RSA_PSK_WITH_NULL_SHA256",
                    "TLS_RSA_PSK_WITH_NULL_SHA384",
                    "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256",
                    "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
                    "TLS_ECDH_ECDSA_WITH_NULL_SHA",
                    "TLS_ECDH_ECDSA_WITH_RC4_128_SHA",
                    "TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_NULL_SHA",
                    "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
                    "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDH_RSA_WITH_NULL_SHA",
                    "TLS_ECDH_RSA_WITH_RC4_128_SHA",
                    "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_NULL_SHA",
                    "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
                    "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDH_anon_WITH_NULL_SHA",
                    "TLS_ECDH_anon_WITH_RC4_128_SHA",
                    "TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDH_anon_WITH_AES_128_CBC_SHA",
                    "TLS_ECDH_anon_WITH_AES_256_CBC_SHA",
                    "TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA",
                    "TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA",
                    "TLS_SRP_SHA_WITH_AES_128_CBC_SHA",
                    "TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA",
                    "TLS_SRP_SHA_WITH_AES_256_CBC_SHA",
                    "TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_PSK_WITH_RC4_128_SHA",
                    "TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA",
                    "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDHE_PSK_WITH_NULL_SHA",
                    "TLS_ECDHE_PSK_WITH_NULL_SHA256",
                    "TLS_ECDHE_PSK_WITH_NULL_SHA384",
                    "TLS_RSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_RSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DH_anon_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DH_anon_WITH_ARIA_256_CBC_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256",
                    "TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384",
                    "TLS_RSA_WITH_ARIA_128_GCM_SHA256",
                    "TLS_RSA_WITH_ARIA_256_GCM_SHA384",
                    "TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256",
                    "TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384",
                    "TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256",
                    "TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384",
                    "TLS_DH_anon_WITH_ARIA_128_GCM_SHA256",
                    "TLS_DH_anon_WITH_ARIA_256_GCM_SHA384",
                    "TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256",
                    "TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384",
                    "TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256",
                    "TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384",
                    "TLS_PSK_WITH_ARIA_128_CBC_SHA256",
                    "TLS_PSK_WITH_ARIA_256_CBC_SHA384",
                    "TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256",
                    "TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384",
                    "TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256",
                    "TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384",
                    "TLS_PSK_WITH_ARIA_128_GCM_SHA256",
                    "TLS_PSK_WITH_ARIA_256_GCM_SHA384",
                    "TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256",
                    "TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384",
                    "TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256",
                    "TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256",
                    "TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384",
                    "TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256",
                    "TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384",
                    "TLS_RSA_WITH_AES_128_CCM",
                    "TLS_RSA_WITH_AES_256_CCM",
                    "TLS_RSA_WITH_AES_128_CCM_8",
                    "TLS_RSA_WITH_AES_256_CCM_8",
                    "TLS_PSK_WITH_AES_128_CCM",
                    "TLS_PSK_WITH_AES_256_CCM",
                    "TLS_PSK_WITH_AES_128_CCM_8",
                    "TLS_PSK_WITH_AES_256_CCM_8"
            );

    private SslContextUtil() {}
}
