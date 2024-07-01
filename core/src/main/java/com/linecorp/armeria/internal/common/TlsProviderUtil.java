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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.CloseableMeterBinder;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

public final class TlsProviderUtil {

    @VisibleForTesting
    static final LoadingCache<TlsCacheContext, SslContext> sslContextCache;

    static {
        final ScheduledExecutorService cacheExecutor = Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.newThreadFactory("armeria-tls-cache-executor", true));

        sslContextCache =
                Caffeine.newBuilder()
                        .maximumSize(1024)
                        .scheduler(Scheduler.forScheduledExecutorService(cacheExecutor))
                        // Remove the cached SslContext after 1 hour of inactivity.
                        .expireAfterAccess(Duration.ofHours(1))
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

    public static SslContext getOrCreateSslContext(TlsProvider tlsProvider, @Nullable TlsKeyPair tlsKeyPair,
                                                   SslContextType type, TlsEngineType tlsEngineType) {
        final TlsCacheContext key = new TlsCacheContext(tlsProvider, tlsKeyPair, type, tlsEngineType);
        final SslContext sslContext = sslContextCache.get(key);
        return requireNonNull(sslContext, "sslContextCache.get() returned null");
    }

    private static SslContext newSslContext(TlsCacheContext key) {
        final TlsKeyPair tlsKeyPair = key.tlsKeyPair;
        final TlsProvider tlsProvider = key.tlsProvider;
        if (key.sslContextType == SslContextType.SERVER) {
            assert tlsKeyPair != null;
            return SslContextUtil.createSslContext(
                    () -> SslContextBuilder.forServer(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain()),
                    false, key.tlsEngineType, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        } else {
            final boolean forceHttp1 =
                    key.sslContextType == SslContextType.CLIENT_HTTP1_ONLY;
            return SslContextUtil.createSslContext(
                    () -> {
                        final SslContextBuilder contextBuilder = SslContextBuilder.forClient();
                        if (tlsKeyPair != null) {
                            contextBuilder.keyManager(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain());
                        }
                        return contextBuilder;
                    },
                    forceHttp1, key.tlsEngineType, tlsProvider.allowsUnsafeCiphers(),
                    tlsProvider.tlsCustomizer(), null);
        }
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

    private static final class TlsCacheContext {

        private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
                new MeterIdPrefix("armeria.server", "hostname.pattern", "UNKNOWN");
        private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
                new MeterIdPrefix("armeria.client");

        final TlsProvider tlsProvider;
        @Nullable
        final TlsKeyPair tlsKeyPair;
        final SslContextType sslContextType;
        final TlsEngineType tlsEngineType;
        @Nullable
        final CloseableMeterBinder meterBinder;

        TlsCacheContext(TlsProvider tlsProvider, @Nullable TlsKeyPair tlsKeyPair,
                        SslContextType sslContextType, TlsEngineType tlsEngineType) {
            // A TlsKeyPair must exist for a server.
            assert sslContextType != SslContextType.SERVER || tlsKeyPair != null;

            this.tlsProvider = tlsProvider;
            this.tlsKeyPair = tlsKeyPair;
            this.sslContextType = sslContextType;
            this.tlsEngineType = tlsEngineType;

            MeterIdPrefix meterIdPrefix = tlsProvider.meterIdPrefix();
            if (meterIdPrefix == null) {
                if (sslContextType == SslContextType.SERVER) {
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
        public int hashCode() {
            // Don't include meterBinder in hashCode() and equals() because it's not used for caching.
            return Objects.hash(tlsProvider, tlsKeyPair, sslContextType, tlsEngineType);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof TlsCacheContext)) {
                return false;
            }

            final TlsCacheContext that = (TlsCacheContext) obj;
            return tlsProvider.equals(that.tlsProvider) &&
                   Objects.equals(tlsKeyPair, that.tlsKeyPair) &&
                   sslContextType == that.sslContextType &&
                   tlsEngineType == that.tlsEngineType;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .omitNullValues()
                              .add("tlsProvider", tlsProvider)
                              .add("tlsKeyPair", tlsKeyPair)
                              .add("sslContextType", sslContextType)
                              .add("tlsEngineType", tlsEngineType)
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
