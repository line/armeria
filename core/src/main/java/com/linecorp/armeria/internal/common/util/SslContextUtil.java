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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;

/**
 * Utilities for configuring {@link SslContextBuilder}.
 */
public final class SslContextUtil {

    private static final Logger logger = LoggerFactory.getLogger(SslContextUtil.class);

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

    public static final List<String> DEFAULT_CIPHERS = ImmutableList.<String>builder()
            .addAll(TLS_V13_CIPHERS)
            .addAll(Http2SecurityUtil.CIPHERS)
            .build();

    public static final List<String> DEFAULT_PROTOCOLS = ImmutableList.of("TLSv1.3", "TLSv1.2");

    private static boolean warnedUnsupportedProtocols;

    /**
     * Creates a {@link SslContext} with Armeria's defaults, enabling support for HTTP/2,
     * TLSv1.3 (if supported), and TLSv1.2.
     */
    public static SslContext createSslContext(
            Supplier<SslContextBuilder> builderSupplier, boolean forceHttp1,
            Iterable<? extends Consumer<? super SslContextBuilder>> userCustomizers) {

        return BouncyCastleKeyFactoryProvider.call(() -> {
            final SslContextBuilder builder = builderSupplier.get();
            final SslProvider provider = Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK;
            builder.sslProvider(provider);

            final Set<String> supportedProtocols = supportedProtocols(builder);
            final List<String> protocols = DEFAULT_PROTOCOLS.stream()
                                                            .filter(supportedProtocols::contains)
                                                            .collect(toImmutableList());
            if (protocols.isEmpty()) {
                throw new IllegalStateException(provider + " supports none of " + DEFAULT_PROTOCOLS);
            }

            if (!warnedUnsupportedProtocols && DEFAULT_PROTOCOLS.size() != protocols.size()) {
                warnedUnsupportedProtocols = true;
                if (logger.isDebugEnabled()) {
                    final List<String> missingProtocols = DEFAULT_PROTOCOLS.stream()
                                                                           .filter(p -> !protocols.contains(p))
                                                                           .collect(toImmutableList());
                    logger.debug("{} does not support: {}", provider, missingProtocols);
                }
            }

            builder.protocols(protocols.toArray(EmptyArrays.EMPTY_STRINGS))
                   .ciphers(DEFAULT_CIPHERS, SupportedCipherSuiteFilter.INSTANCE);

            userCustomizers.forEach(customizer -> customizer.accept(builder));

            // We called user customization logic before setting ALPN to make sure they don't break
            // compatibility with HTTP/2.
            if (!forceHttp1) {
                builder.applicationProtocolConfig(ALPN_CONFIG);
            }

            SslContext sslContext = null;
            boolean success = false;
            try {
                sslContext = builder.build();

                final Set<String> ciphers = ImmutableSet.copyOf(sslContext.cipherSuites());
                checkState(!ciphers.isEmpty(), "SSLContext has no cipher suites enabled.");

                if (!forceHttp1) {
                    validateHttp2Ciphers(ciphers);
                }

                success = true;
                return sslContext;
            } catch (SSLException e) {
                throw new IllegalStateException(
                        "Could not initialize SSL context. Ensure that netty-tcnative is " +
                        "on the path, this is running on Java 11+, or user customization " +
                        "of the SSL context is supported by the environment.", e);
            } finally {
                if (!success && sslContext != null) {
                    ReferenceCountUtil.release(sslContext);
                }
            }
        });
    }

    @VisibleForTesting
    static Set<String> supportedProtocols(SslContextBuilder builder) {
        SslContext ctx = null;
        SSLEngine engine = null;
        try {
            ctx = builder.build();
            engine = ctx.newEngine(PooledByteBufAllocator.DEFAULT);
            return ImmutableSet.copyOf(engine.getSupportedProtocols());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to get the list of supported protocols from an SSLContext.", e);
        } finally {
            ReferenceCountUtil.release(engine);
            ReferenceCountUtil.release(ctx);
        }
    }

    private static void validateHttp2Ciphers(Set<String> ciphers) {
        if (!ciphers.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")) {
            throw new IllegalStateException("Attempting to configure a server or HTTP/2 client without the " +
                                            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher enabled. This " +
                                            "cipher must be enabled for HTTP/2 support.");
        }

        for (String cipher : ciphers) {
            if (HTTP2_BLACKLISTED_CIPHERS.contains(cipher)) {
                throw new IllegalStateException(
                        "Attempted to configure a server or HTTP/2 client with a TLS cipher that is not " +
                        "allowed. Please remove any ciphers from the HTTP/2 cipher blacklist " +
                        "https://httpwg.org/specs/rfc7540.html#BadCipherSuites");
            }
        }
    }

    // https://httpwg.org/specs/rfc7540.html#BadCipherSuites
    private static final Set<String> HTTP2_BLACKLISTED_CIPHERS =
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
