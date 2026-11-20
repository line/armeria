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

import static com.linecorp.armeria.internal.common.util.SslContextUtil.BAD_HTTP2_CIPHERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TlsEngineType;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

class SslContextUtilTest {

    @Test
    void openSsl() {
        final Set<String> supportedProtocols = SslContextUtil.supportedTlsVersions(SslProvider.OPENSSL);
        assertThat(supportedProtocols).contains("TLSv1.2", "TLSv1.3");
    }

    @Test
    void jdkSsl() {
        final Set<String> supportedProtocols = SslContextUtil.supportedTlsVersions(SslProvider.JDK);
        if (SystemInfo.javaVersion() >= 11) {
            assertThat(supportedProtocols).contains("TLSv1.2", "TLSv1.3");
        } else {
            assertThat(supportedProtocols).contains("TLSv1.2");
        }
    }

    @Test
    void unsafeTlsCiphers() {
        final String cipher = getBadCipher();
        assumeThat(cipher).isNotNull();

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsCustomizer(builder -> builder.ciphers(ImmutableList.of(cipher)))
                                 .build();
            factory.closeAsync();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsAllowUnsafeCiphers()
                             .tlsCustomizer(builder -> builder.ciphers(ImmutableList.of(cipher)))
                             .build();
        factory.closeAsync();
    }

    @Test
    void tlsSpecForUnsafeTlsCipher() {
        final String cipher = getBadCipher();
        assumeThat(cipher).isNotNull();

        final SslContext invalidContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder()
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .ciphers(BAD_HTTP2_CIPHERS)
                             .build());
        try {
            assertThatThrownBy(() -> {
                final SSLEngine engine = invalidContext.newEngine(ByteBufAllocator.DEFAULT);
                ReferenceCountUtil.release(engine);
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
        } finally {
            ReferenceCountUtil.release(invalidContext);
        }

        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder().ciphers(BAD_HTTP2_CIPHERS)
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .allowUnsafeCiphers(true)
                             .build());
        try {
            final SSLEngine engine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
            ReferenceCountUtil.release(engine);
        } finally {
            ReferenceCountUtil.release(sslContext);
        }
    }

    @Test
    void tls13OnlyWithEssentialCipher() {
        assumeThat(SslContextUtil.supportedTlsVersions(Flags.tlsEngineType().sslProvider()))
                .contains("TLSv1.3");

        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsCustomizer(builder -> {
                                 builder.protocols("TLSv1.3");
                                 builder.ciphers(ImmutableList.of("TLS_AES_128_GCM_SHA256"));
                             })
                             .build();
        factory.closeAsync();
    }

    @Test
    void tls13OnlyWithoutEssentialCipher() {
        assumeThat(SslContextUtil.supportedTlsVersions(Flags.tlsEngineType().sslProvider()))
                .contains("TLSv1.3");

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.3");
                                     builder.ciphers(ImmutableList.of("TLS_AES_256_GCM_SHA384"));
                                 })
                                 .build();
            factory.closeAsync();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_AES_128_GCM_SHA256 cipher suite enabled");
    }

    @Test
    void tls13OnlyWithoutEssentialCipherAllowed() {
        assumeThat(SslContextUtil.supportedTlsVersions(Flags.tlsEngineType().sslProvider()))
                .contains("TLSv1.3");

        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsAllowUnsafeCiphers()
                             .tlsCustomizer(builder -> {
                                 builder.protocols("TLSv1.3");
                                 builder.ciphers(ImmutableList.of("TLS_AES_256_GCM_SHA384"));
                             })
                             .build();
        factory.closeAsync();
    }

    @Test
    void tls12OnlyWithoutEssentialCipher() {
        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.2");
                                     builder.ciphers(ImmutableList.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"));
                                 })
                                 .build();
            factory.closeAsync();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
    }

    @Test
    void bothProtocolsWithoutTls12EssentialCipher() {
        assumeThat(SslContextUtil.supportedTlsVersions(Flags.tlsEngineType().sslProvider()))
                .contains("TLSv1.3");

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.2", "TLSv1.3");
                                     builder.ciphers(ImmutableList.of("TLS_AES_128_GCM_SHA256"));
                                 })
                                 .build();
            factory.closeAsync();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
    }

    @Test
    void validateHttp2CiphersTls13MissingEssentialCipher() {
        final Set<String> ciphers = ImmutableSet.of("TLS_AES_256_GCM_SHA384");
        assertThatThrownBy(() -> SslContextUtil.validateHttp2Ciphers(ciphers, false,
                                                                     ImmutableSet.of("TLSv1.3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS without the TLS_AES_128_GCM_SHA256 cipher suite enabled");
    }

    @Test
    void validateHttp2CiphersTls13MissingEssentialCipherAllowed() {
        final Set<String> ciphers = ImmutableSet.of("TLS_AES_256_GCM_SHA384");
        SslContextUtil.validateHttp2Ciphers(ciphers, true, ImmutableSet.of("TLSv1.3"));
    }

    @Test
    void validateHttp2CiphersTls12MissingEssentialCipher() {
        final Set<String> ciphers = ImmutableSet.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        assertThatThrownBy(() -> SslContextUtil.validateHttp2Ciphers(ciphers, false,
                                                                     ImmutableSet.of("TLSv1.2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
    }

    @Test
    void validationRunsOnNewEngine() {
        final String cipher = getBadCipher();
        assumeThat(cipher).isNotNull();

        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder()
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .ciphers(BAD_HTTP2_CIPHERS)
                             .build());
        try {
            assertThatThrownBy(() -> {
                final SSLEngine engine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
                ReferenceCountUtil.release(engine);
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
        } finally {
            ReferenceCountUtil.release(sslContext);
        }
    }

    @Test
    void openSslWrapperForwardsRelease() {
        assumeThat(OpenSsl.isAvailable()).isTrue();
        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder()
                             .engineType(TlsEngineType.OPENSSL)
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .build());
        assertThat(sslContext).isInstanceOf(ReferenceCounted.class);
        final ReferenceCounted refCounted = (ReferenceCounted) sslContext;
        assertThat(refCounted.refCnt()).isEqualTo(1);
        assertThat(refCounted.retain()).isSameAs(sslContext);
        assertThat(refCounted.refCnt()).isEqualTo(2);
        assertThat(refCounted.touch()).isSameAs(sslContext);
        ReferenceCountUtil.release(sslContext);
        assertThat(refCounted.refCnt()).isEqualTo(1);
        ReferenceCountUtil.release(sslContext);
        assertThat(refCounted.refCnt()).isEqualTo(0);
    }

    @Test
    void validationKeepsFailingOnSubsequentNewEngine() {
        final String cipher = getBadCipher();
        assumeThat(cipher).isNotNull();

        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder()
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .ciphers(BAD_HTTP2_CIPHERS)
                             .build());
        try {
            assertThatThrownBy(() -> {
                final SSLEngine engine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
                ReferenceCountUtil.release(engine);
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

            assertThatThrownBy(() -> {
                final SSLEngine engine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
                ReferenceCountUtil.release(engine);
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
        } finally {
            ReferenceCountUtil.release(sslContext);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TlsEngineType.class, names = { "JDK", "OPENSSL" })
    void newHandlerThrowsOnBadCipher(TlsEngineType engineType) {
        if (engineType == TlsEngineType.OPENSSL) {
            assumeThat(OpenSsl.isAvailable()).isTrue();
        }
        final String cipher = getBadCipher();
        assumeThat(cipher).isNotNull();

        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder()
                             .engineType(engineType)
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .ciphers(BAD_HTTP2_CIPHERS)
                             .build());
        try {
            assertThatThrownBy(() -> sslContext.newHandler(ByteBufAllocator.DEFAULT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

            assertThatThrownBy(() -> sslContext.newHandler(ByteBufAllocator.DEFAULT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
        } finally {
            ReferenceCountUtil.release(sslContext);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TlsEngineType.class, names = { "JDK", "OPENSSL" })
    void protocolCombinations(TlsEngineType engineType) {
        if (engineType == TlsEngineType.OPENSSL) {
            assumeThat(OpenSsl.isAvailable()).isTrue();
        }
        final Set<String> supported = SslContextUtil.supportedTlsVersions(engineType.sslProvider());

        if (supported.contains("TLSv1.3")) {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsEngineType(engineType)
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.3");
                                     builder.ciphers(ImmutableList.of("TLS_AES_128_GCM_SHA256"));
                                 })
                                 .build();
            factory.closeAsync();
        }

        if (supported.contains("TLSv1.3")) {
            assertThatThrownBy(() -> {
                final ClientFactory factory =
                        ClientFactory.builder()
                                     .tlsEngineType(engineType)
                                     .tlsCustomizer(builder -> {
                                         builder.protocols("TLSv1.3");
                                         builder.ciphers(ImmutableList.of("TLS_AES_256_GCM_SHA384"));
                                     })
                                     .build();
                factory.closeAsync();
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_AES_128_GCM_SHA256 cipher suite enabled");
        }

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsEngineType(engineType)
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.2");
                                     builder.ciphers(ImmutableList.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"));
                                 })
                                 .build();
            factory.closeAsync();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

        if (supported.contains("TLSv1.3")) {
            assertThatThrownBy(() -> {
                final ClientFactory factory =
                        ClientFactory.builder()
                                     .tlsEngineType(engineType)
                                     .tlsCustomizer(builder -> {
                                         builder.protocols("TLSv1.2", "TLSv1.3");
                                         builder.ciphers(ImmutableList.of("TLS_AES_128_GCM_SHA256"));
                                     })
                                     .build();
                factory.closeAsync();
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
        }
    }

    @Nullable
    private static String getBadCipher() {
        for (String cipher : BAD_HTTP2_CIPHERS) {
            try {
                final SslContext sslCtx = MinifiedBouncyCastleProvider.call(() -> {
                    final SslContextBuilder builder = SslContextBuilder.forClient();
                    final SslProvider provider = Flags.tlsEngineType().sslProvider();
                    builder.sslProvider(provider);
                    builder.protocols("TLSv1.2").ciphers(ImmutableList.of(cipher));

                    try {
                        return builder.build();
                    } catch (SSLException e) {
                        return Exceptions.throwUnsafely(e);
                    }
                });
                ReferenceCountUtil.release(sslCtx);
                return cipher;
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }
}
