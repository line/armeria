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

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SystemInfo;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;

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

        assertThatThrownBy(() -> {
            SslContextUtil.toSslContext(ClientTlsSpec.builder()
                                                     .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                                                     .ciphers(BAD_HTTP2_CIPHERS)
                                                     .build());
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

        final SslContext sslContext = SslContextUtil.toSslContext(
                ClientTlsSpec.builder().ciphers(BAD_HTTP2_CIPHERS)
                             .alpnProtocols(SslContextUtil.DEFAULT_ALPN_PROTOCOLS)
                             .allowUnsafeCiphers(true)
                             .build());
        ReferenceCountUtil.release(sslContext);
    }

    @Test
    void tls13OnlyWithEssentialCipher() {
        assumeThat(SslContextUtil.supportedTlsVersions(Flags.tlsEngineType().sslProvider()))
                .contains("TLSv1.3");

        // When only TLS 1.3 is configured, TLS_AES_128_GCM_SHA256 is the essential cipher.
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

        // When only TLS 1.3 is configured and TLS_AES_128_GCM_SHA256 is missing,
        // it must throw an IllegalStateException mentioning the missing cipher.
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

        // When allowUnsafeCiphers is true, omitting the essential TLS 1.3 cipher
        // should warn instead of throwing.
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
        // When only TLS 1.2 is configured without TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        // it must throw an IllegalStateException mentioning the TLS 1.2 cipher.
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

        // When both TLS 1.2 and TLS 1.3 are enabled, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 is still required.
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
    void validateHttp2CiphersBothEssentialPresent() {
        final Set<String> ciphers = ImmutableSet.of("TLS_AES_128_GCM_SHA256",
                                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        SslContextUtil.validateHttp2Ciphers(ciphers, false, () -> {
            throw new AssertionError("Supplier should not be called when both essential ciphers are present");
        });
    }

    @Test
    void validateHttp2CiphersTls13MissingEssentialCipher() {
        final Set<String> ciphers = ImmutableSet.of("TLS_AES_256_GCM_SHA384");
        assertThatThrownBy(() -> SslContextUtil.validateHttp2Ciphers(ciphers, false,
                                                                     () -> ImmutableSet.of("TLSv1.3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS without the TLS_AES_128_GCM_SHA256 cipher suite enabled");
    }

    @Test
    void validateHttp2CiphersTls13MissingEssentialCipherAllowed() {
        final Set<String> ciphers = ImmutableSet.of("TLS_AES_256_GCM_SHA384");
        SslContextUtil.validateHttp2Ciphers(ciphers, true, () -> ImmutableSet.of("TLSv1.3"));
    }

    @Test
    void validateHttp2CiphersTls12MissingEssentialCipher() {
        final Set<String> ciphers = ImmutableSet.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        assertThatThrownBy(() -> SslContextUtil.validateHttp2Ciphers(ciphers, false,
                                                                     () -> ImmutableSet.of("TLSv1.2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
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
