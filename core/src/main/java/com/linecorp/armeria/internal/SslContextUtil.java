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

package com.linecorp.armeria.internal;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

/**
 * Utilities for configuring {@link SslContextBuilder}.
 */
public final class SslContextUtil {

    private static final ApplicationProtocolConfig ALPN_CONFIG = new ApplicationProtocolConfig(
            Protocol.ALPN,
            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
            SelectorFailureBehavior.NO_ADVERTISE,
            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1);

    // OpenSSL's default enabled TLSv1.3 ciphers as documented at https://wiki.openssl.org/index.php/TLS1.3
    private static final List<String> TLS_V13_CIPHERS = ImmutableList.of("TLS_AES_256_GCM_SHA384" ,
                                                                         "TLS_CHACHA20_POLY1305_SHA256",
                                                                         "TLS_AES_128_GCM_SHA256");

    public static final List<String> DEFAULT_CIPHERS = ImmutableList.<String>builder()
            .addAll(TLS_V13_CIPHERS)
            .addAll(Http2SecurityUtil.CIPHERS)
            .build();

    public static final List<String> DEFAULT_PROTOCOLS = ImmutableList.of("TLSv1.3", "TLSv1.2");

    /**
     * Configures a {@link SslContextBuilder} with Armeria's defaults, enabling support for HTTP/2, TLSv1.3, and
     * TLSv1.2.
     */
    public static void configureDefaults(SslContextBuilder sslContext, boolean forceHttp1) {
        sslContext.sslProvider(Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK)
                  .protocols(DEFAULT_PROTOCOLS.toArray(new String[0]))
                  .ciphers(DEFAULT_CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
        if (!forceHttp1) {
            sslContext.applicationProtocolConfig(ALPN_CONFIG);
        }
    }

    private SslContextUtil() {}
}
