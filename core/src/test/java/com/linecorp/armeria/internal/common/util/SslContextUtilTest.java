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

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SystemInfo;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;

class SslContextUtilTest {

    @Test
    void openSsl() {
        assumeThat(Flags.useOpenSsl()).isTrue();
        final Set<String> supportedProtocols = SslContextUtil.supportedProtocols(
                SslContextBuilder.forClient().sslProvider(SslProvider.OPENSSL));
        assertThat(supportedProtocols).contains("TLSv1.2", "TLSv1.3");
    }

    @Test
    void jdkSsl() {
        final Set<String> supportedProtocols = SslContextUtil.supportedProtocols(
                SslContextBuilder.forClient().sslProvider(SslProvider.JDK));
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
          .hasMessageContaining("Attempting to configure a server or HTTP/2 client without");

        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsAllowUnsafeCiphers()
                             .tlsCustomizer(builder -> builder.ciphers(ImmutableList.of(cipher)))
                             .build();
        factory.closeAsync();
    }

    @Nullable
    private static String getBadCipher() {
        for (String cipher : BAD_HTTP2_CIPHERS) {
            try {
                final SslContext sslCtx = BouncyCastleKeyFactoryProvider.call(() -> {
                    final SslContextBuilder builder = SslContextBuilder.forClient();
                    final SslProvider provider = Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK;
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
