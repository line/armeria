/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import java.net.IDN;
import java.util.Locale;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.CloseableMeterBinder;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

public final class TlsProviderUtil {

    private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.server", "hostname.pattern", "UNKNOWN");
    private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.client");

    public static ReleasableHolder<SslContext> getOrCreateSslContext(TlsProvider tlsProvider,
                                                                     @Nullable TlsKeyPair tlsKeyPair,
                                                                     SslContextType type,
                                                                     TlsEngineType tlsEngineType) {
        final SslContext sslContext;
        if (type == SslContextType.SERVER) {
            // A TlsKeyPair must exist for a server.
            assert tlsKeyPair != null;
            sslContext = SslContextUtil.createSslContext(
                    () -> SslContextBuilder.forServer(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain()),
                    false, tlsEngineType, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        } else {
            final boolean forceHttp1 = type == SslContextType.CLIENT_HTTP1_ONLY;
            sslContext = SslContextUtil.createSslContext(
                    () -> {
                        final SslContextBuilder contextBuilder = SslContextBuilder.forClient();
                        if (tlsKeyPair != null) {
                            contextBuilder.keyManager(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain());
                        }
                        return contextBuilder;
                    },
                    forceHttp1, tlsEngineType, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        }
        return new SslContextHolder(sslContext, type, tlsKeyPair, tlsProvider.meterIdPrefix());
    }

    // Forked from https://github.com/netty/netty/blob/60430c80e7f8718ecd07ac31e01297b42a176b87/common/src/main/java/io/netty/util/DomainWildcardMappingBuilder.java#L78

    /**
     * IDNA ASCII conversion and case normalization.
     */
    public static String normalizeHostname(String hostname) {
        if (hostname.isEmpty() || hostname.charAt(0) == '.') {
            throw new IllegalArgumentException("Hostname '" + hostname + "' not valid");
        }
        if (needsNormalization(hostname)) {
            hostname = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED);
        }
        hostname = hostname.toLowerCase(Locale.US);

        if (hostname.charAt(0) == '*') {
            if (hostname.length() < 3 || hostname.charAt(1) != '.') {
                throw new IllegalArgumentException("Wildcard Hostname '" + hostname + "'not valid");
            }
            return hostname.substring(1);
        }
        return hostname;
    }

    private static boolean needsNormalization(String hostname) {
        final int length = hostname.length();
        for (int i = 0; i < length; i++) {
            final int c = hostname.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static class SslContextHolder implements ReleasableHolder<SslContext> {

        private final SslContext sslContext;
        @Nullable
        private final CloseableMeterBinder meterBinder;

        SslContextHolder(SslContext sslContext, SslContextType type,
                         @Nullable TlsKeyPair tlsKeyPair, @Nullable MeterIdPrefix meterIdPrefix) {

            this.sslContext = sslContext;

            if (meterIdPrefix == null) {
                if (type == SslContextType.SERVER) {
                    meterIdPrefix = SERVER_METER_ID_PREFIX;
                } else {
                    meterIdPrefix = CLIENT_METER_ID_PREFIX;
                }
            }
            if (tlsKeyPair != null) {
                meterBinder = MoreMeterBinders.certificateMetrics(tlsKeyPair.certificateChain(), meterIdPrefix);
            } else {
                meterBinder = null;
            }
        }

        @Override
        public SslContext get() {
            return sslContext;
        }

        @Override
        public void release() {
            ReferenceCountUtil.release(sslContext);
            if (meterBinder != null) {
                meterBinder.close();
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("sslContext", sslContext)
                              .add("meterBinder", meterBinder)
                              .toString();
        }
    }

    public enum SslContextType {
        SERVER,
        CLIENT_HTTP1_ONLY,
        CLIENT
    }

    private TlsProviderUtil() {}
}
