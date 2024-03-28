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

import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.CertificateMetrics;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Mapping;
import io.netty.util.ReferenceCountUtil;

public final class TlsProviderUtil {

    private static final LoadingCache<TlsCacheContext, SslContext> sslContextCache;

    static {
        final ScheduledExecutorService cacheExecutor = Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.newThreadFactory("armeria-tls-cache-executor", true));

        sslContextCache =
                Caffeine.newBuilder()
                        .maximumSize(1024)
                        .scheduler(Scheduler.forScheduledExecutorService(cacheExecutor))
                        // To clean up metrics for unused certs.
                        .expireAfterAccess(Duration.ofDays(1))
                        .removalListener((TlsCacheContext key, SslContext value, RemovalCause cause) -> {
                            if (value != null) {
                                ReferenceCountUtil.release(value);
                            }
                            if (key != null) {
                                // Remove the registered metrics.
                                key.meterBinder.close();
                            }
                        })
                        .build(TlsProviderUtil::newSslContext);
    }

    public static Mapping<@Nullable String, SslContext> toSslContextMapping(TlsProvider tlsProvider) {
        return hostname -> {
            if (hostname == null) {
                hostname = "*";
            } else {
                hostname = normalizeHostname(hostname);
            }
            TlsKeyPair tlsKeyPair = tlsProvider.find(hostname);
            if (tlsKeyPair == null) {
                // Try to find the default TLS key pair.
                tlsKeyPair = tlsProvider.find("*");
            }
            if (tlsKeyPair == null) {
                throw new IllegalStateException("No TLS key pair found for " + hostname);
            }
            return maybeCreateSslContext(tlsProvider, tlsKeyPair, SslContextType.SERVER);
        };
    }

    public static SslContext maybeCreateSslContext(TlsProvider tlsProvider, TlsKeyPair tlsKeyPair,
                                                   SslContextType type) {
        final TlsCacheContext key = new TlsCacheContext(tlsProvider, tlsKeyPair, type);
        final SslContext sslContext = sslContextCache.get(key);
        return requireNonNull(sslContext, "sslContextCache.get() returned null");
    }

    private static SslContext newSslContext(TlsCacheContext key) {
        final TlsKeyPair tlsKeyPair = key.tlsKeyPair;
        final TlsProvider tlsProvider = key.tlsProvider;
        if (key.type == SslContextType.SERVER) {
            return SslContextUtil.createSslContext(
                    () -> SslContextBuilder.forServer(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain()),
                    false, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        } else {
            final boolean forceHttp1 =
                    key.type == SslContextType.CLIENT_HTTP1_ONLY;
            return SslContextUtil.createSslContext(
                    () -> SslContextBuilder.forClient()
                                           .keyManager(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain()),
                    forceHttp1, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        }
    }

    // Forked from https://github.com/netty/netty/blob/60430c80e7f8718ecd07ac31e01297b42a176b87/common/src/main/java/io/netty/util/DomainNameMapping.java

    /**
     * IDNA ASCII conversion and case normalization.
     */
    public static String normalizeHostname(String hostname) {
        if (needsNormalization(hostname)) {
            hostname = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED);
        }
        return hostname.toLowerCase(Locale.US);
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

    private static final class TlsCacheContext {

        private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
                new MeterIdPrefix("armeria.server", "hostname.pattern", "UNKNOWN");
        private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
                new MeterIdPrefix("armeria.client");

        final TlsProvider tlsProvider;
        final TlsKeyPair tlsKeyPair;
        final SslContextType type;
        final CertificateMetrics meterBinder;

        TlsCacheContext(TlsProvider tlsProvider, TlsKeyPair tlsKeyPair, SslContextType type) {
            this.tlsProvider = tlsProvider;
            this.tlsKeyPair = tlsKeyPair;
            this.type = type;

            MeterIdPrefix meterIdPrefix = tlsProvider.meterIdPrefix();
            if (meterIdPrefix == null) {
                if (type == SslContextType.SERVER) {
                    meterIdPrefix = SERVER_METER_ID_PREFIX;
                } else {
                    meterIdPrefix = CLIENT_METER_ID_PREFIX;
                }
            }
            meterBinder = MoreMeterBinders.certificateMetrics(tlsKeyPair.certificateChain(), meterIdPrefix);
        }

        @Override
        public int hashCode() {
            // Don't include meterBinder in hashCode() and equals() because it's not used for caching.
            return tlsProvider.hashCode() * 31 + tlsKeyPair.hashCode() * 31 + type.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof TlsCacheContext)) {
                return false;
            }

            final TlsCacheContext that = (TlsCacheContext) obj;
            return tlsProvider.equals(that.tlsProvider) &&
                   tlsKeyPair.equals(that.tlsKeyPair) &&
                   type == that.type;
        }
    }

    public enum SslContextType {
        SERVER,
        CLIENT_HTTP1_ONLY,
        CLIENT
    }

    private TlsProviderUtil() {}
}
