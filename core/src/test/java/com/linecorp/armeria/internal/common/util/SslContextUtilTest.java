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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Set;

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

import io.netty.handler.ssl.OpenSsl;
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

    @ParameterizedTest
    @EnumSource(value = TlsEngineType.class, names = { "JDK", "OPENSSL" })
    void tls13OnlyDoesNotRequireTls12Cipher(TlsEngineType engineType) {
        if (engineType == TlsEngineType.OPENSSL) {
            assumeThat(OpenSsl.isAvailable()).isTrue();
        }
        assumeThat(SslContextUtil.supportedTlsVersions(engineType.sslProvider())).contains("TLSv1.3");

        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsEngineType(engineType)
                             .tlsCustomizer(builder -> {
                                 builder.protocols("TLSv1.3");
                                 builder.ciphers(ImmutableList.of("TLS_AES_256_GCM_SHA384"));
                             })
                             .build();
        factory.close();
    }

    @ParameterizedTest
    @EnumSource(value = TlsEngineType.class, names = { "JDK", "OPENSSL" })
    void tls12OnlyRequiresTls12Cipher(TlsEngineType engineType) {
        if (engineType == TlsEngineType.OPENSSL) {
            assumeThat(OpenSsl.isAvailable()).isTrue();
        }
        assumeThat(SslContextUtil.supportedTlsVersions(engineType.sslProvider())).contains("TLSv1.3");

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsEngineType(engineType)
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.2");
                                     builder.ciphers(ImmutableList.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"));
                                 })
                                 .build();
            factory.close();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
    }

    @ParameterizedTest
    @EnumSource(value = TlsEngineType.class, names = { "JDK", "OPENSSL" })
    void bothProtocolsRequireTls12Cipher(TlsEngineType engineType) {
        if (engineType == TlsEngineType.OPENSSL) {
            assumeThat(OpenSsl.isAvailable()).isTrue();
        }
        assumeThat(SslContextUtil.supportedTlsVersions(engineType.sslProvider())).contains("TLSv1.3");

        assertThatThrownBy(() -> {
            final ClientFactory factory =
                    ClientFactory.builder()
                                 .tlsEngineType(engineType)
                                 .tlsCustomizer(builder -> {
                                     builder.protocols("TLSv1.2", "TLSv1.3");
                                     builder.ciphers(ImmutableList.of("TLS_AES_128_GCM_SHA256"));
                                 })
                                 .build();
            factory.close();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");
    }

    @Test
    void validateHttp2Ciphers() {
        assertThatCode(() -> {
            SslContextUtil.validateHttp2Ciphers(
                    ImmutableSet.of("TLS_AES_256_GCM_SHA384"), false, ImmutableSet.of("TLSv1.3"));
            SslContextUtil.validateHttp2Ciphers(
                    ImmutableSet.of("TLS_AES_256_GCM_SHA384"), false,
                    ImmutableSet.of("SSLv2Hello", "TLSv1.3"));
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> {
            SslContextUtil.validateHttp2Ciphers(
                    ImmutableSet.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"), false,
                    ImmutableSet.of("TLSv1.2"));
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TLS without the TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 cipher suite");

        assertThatCode(() -> {
            SslContextUtil.validateHttp2Ciphers(
                    ImmutableSet.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"), true,
                    ImmutableSet.of("TLSv1.2"));
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> {
            SslContextUtil.validateHttp2Ciphers(
                    ImmutableSet.of("TLS_AES_128_GCM_SHA256"), false,
                    ImmutableSet.of("TLSv1.2", "TLSv1.3"));
        }).isInstanceOf(IllegalStateException.class)
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
