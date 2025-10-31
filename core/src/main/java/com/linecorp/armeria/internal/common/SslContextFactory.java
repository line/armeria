/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.common.util.SslContextUtil.createSslContext;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.common.AbstractTlsConfig;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.metric.CloseableMeterBinder;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.server.ServerTlsConfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

public final class SslContextFactory {

    private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.server", "hostname.pattern", "UNKNOWN");
    private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.client");

    private final Map<CacheKey, SslContextHolder> cache = new HashMap<>();
    private final Map<SslContext, CacheKey> reverseCache = new HashMap<>();

    private final TlsProvider tlsProvider;
    private final TlsEngineType engineType;
    private final MeterRegistry meterRegistry;
    @Nullable
    private final AbstractTlsConfig tlsConfig;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;
    private final boolean allowsUnsafeCiphers;

    private final ReentrantShortLock lock = new ReentrantShortLock();

    public SslContextFactory(TlsProvider tlsProvider, TlsEngineType engineType,
                             @Nullable AbstractTlsConfig tlsConfig, MeterRegistry meterRegistry) {
        // TODO(ikhoon): Support OPENSSL_REFCNT engine type.
        assert engineType.sslProvider() != SslProvider.OPENSSL_REFCNT;

        this.tlsProvider = tlsProvider;
        this.engineType = engineType;
        this.meterRegistry = meterRegistry;
        if (tlsConfig != null) {
            this.tlsConfig = tlsConfig;
            meterIdPrefix = tlsConfig.meterIdPrefix();
            allowsUnsafeCiphers = tlsConfig.allowsUnsafeCiphers();
        } else {
            this.tlsConfig = null;
            meterIdPrefix = null;
            allowsUnsafeCiphers = false;
        }
    }

    /**
     * Returns an {@link SslContext} for the specified {@link SslContextMode} and {@link TlsKeyPair}.
     * Note that the returned {@link SslContext} should be released via
     * {@link ReferenceCountUtil#release(Object)} when it is no longer used.
     */
    public SslContext getOrCreate(SslContextMode mode, String hostname) {
        lock.lock();
        try {
            final TlsKeyPair tlsKeyPair = findTlsKeyPair(mode, hostname);
            final List<X509Certificate> trustedCertificates = findTrustedCertificates(hostname);
            final CacheKey cacheKey = new CacheKey(mode, tlsKeyPair, trustedCertificates);
            final SslContextHolder contextHolder = cache.computeIfAbsent(cacheKey, this::create);
            contextHolder.retain();
            reverseCache.putIfAbsent(contextHolder.sslContext(), cacheKey);
            return contextHolder.sslContext();
        } finally {
            lock.unlock();
        }
    }

    public void release(SslContext sslContext) {
        lock.lock();
        try {
            final CacheKey cacheKey = reverseCache.get(sslContext);
            final SslContextHolder contextHolder = cache.get(cacheKey);
            assert contextHolder != null : "sslContext not found in the cache: " + sslContext;

            if (contextHolder.release()) {
                final SslContextHolder removed = cache.remove(cacheKey);
                assert removed == contextHolder;
                reverseCache.remove(sslContext);
                contextHolder.destroy();
            }
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    private TlsKeyPair findTlsKeyPair(SslContextMode mode, String hostname) {
        TlsKeyPair tlsKeyPair = tlsProvider.keyPair(hostname);
        if (tlsKeyPair == null) {
            // Try to find the default TLS key pair.
            tlsKeyPair = tlsProvider.keyPair("*");
        }
        if (mode == SslContextMode.SERVER && tlsKeyPair == null) {
            // A TlsKeyPair must exist for a server.
            throw new IllegalStateException("No TLS key pair found for " + hostname);
        }
        return tlsKeyPair;
    }

    private List<X509Certificate> findTrustedCertificates(String hostname) {
        List<X509Certificate> certs = tlsProvider.trustedCertificates(hostname);
        if (certs == null) {
            certs = tlsProvider.trustedCertificates("*");
        }
        return firstNonNull(certs, ImmutableList.of());
    }

    private SslContextHolder create(CacheKey key) {
        final MeterIdPrefix meterIdPrefix = meterIdPrefix(key.mode);
        final SslContext sslContext = newSslContext(key);
        final ImmutableList.Builder<X509Certificate> builder = ImmutableList.builder();
        if (key.tlsKeyPair != null) {
            builder.addAll(key.tlsKeyPair.certificateChain());
        }
        if (!key.trustedCertificates.isEmpty()) {
            builder.addAll(key.trustedCertificates);
        }
        final List<X509Certificate> certs = builder.build();
        CloseableMeterBinder meterBinder = null;
        if (!certs.isEmpty()) {
            meterBinder = MoreMeterBinders.certificateMetrics(certs, meterIdPrefix);
            meterBinder.bindTo(meterRegistry);
        }
        return new SslContextHolder(sslContext, meterBinder);
    }

    private SslContext newSslContext(CacheKey key) {
        final SslContextMode mode = key.mode();
        final TlsKeyPair tlsKeyPair = key.tlsKeyPair();
        final List<X509Certificate> trustedCerts = key.trustedCertificates();
        if (mode == SslContextMode.SERVER) {
            assert tlsKeyPair != null;
            return createSslContext(
                    () -> {
                        final SslContextBuilder contextBuilder = SslContextBuilder.forServer(
                                tlsKeyPair.privateKey(),
                                tlsKeyPair.certificateChain());
                        if (!trustedCerts.isEmpty()) {
                            contextBuilder.trustManager(trustedCerts);
                        }
                        applyTlsConfig(contextBuilder);
                        return contextBuilder;
                    },
                    false, engineType, allowsUnsafeCiphers,
                    null, null);
        } else {
            final boolean forceHttp1 = mode == SslContextMode.CLIENT_HTTP1_ONLY;
            return createSslContext(
                    () -> {
                        final SslContextBuilder contextBuilder = SslContextBuilder.forClient();
                        contextBuilder.endpointIdentificationAlgorithm("HTTPS");
                        if (tlsKeyPair != null) {
                            contextBuilder.keyManager(tlsKeyPair.privateKey(), tlsKeyPair.certificateChain());
                        }
                        if (!trustedCerts.isEmpty()) {
                            contextBuilder.trustManager(trustedCerts);
                        }
                        applyTlsConfig(contextBuilder);
                        return contextBuilder;
                    },
                    forceHttp1, engineType, allowsUnsafeCiphers, null, null);
        }
    }

    private void applyTlsConfig(SslContextBuilder contextBuilder) {
        if (tlsConfig == null) {
            return;
        }

        if (tlsConfig instanceof ServerTlsConfig) {
            final ServerTlsConfig serverTlsConfig = (ServerTlsConfig) tlsConfig;
            contextBuilder.clientAuth(serverTlsConfig.clientAuth());
        } else {
            final ClientTlsConfig clientTlsConfig = (ClientTlsConfig) tlsConfig;
            if (clientTlsConfig.tlsNoVerifySet()) {
                contextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else if (!clientTlsConfig.insecureHosts().isEmpty()) {
                contextBuilder.trustManager(IgnoreHostsTrustManager.of(clientTlsConfig.insecureHosts()));
            }
        }
        tlsConfig.tlsCustomizer().accept(contextBuilder);
    }

    private MeterIdPrefix meterIdPrefix(SslContextMode mode) {
        MeterIdPrefix meterIdPrefix = this.meterIdPrefix;
        if (meterIdPrefix == null) {
            if (mode == SslContextMode.SERVER) {
                meterIdPrefix = SERVER_METER_ID_PREFIX;
            } else {
                meterIdPrefix = CLIENT_METER_ID_PREFIX;
            }
        }
        return meterIdPrefix;
    }

    @VisibleForTesting
    public int numCachedContexts() {
        return cache.size();
    }

    public enum SslContextMode {
        SERVER,
        CLIENT_HTTP1_ONLY,
        CLIENT
    }

    private static final class CacheKey {
        private final SslContextMode mode;
        @Nullable
        private final TlsKeyPair tlsKeyPair;

        private final List<X509Certificate> trustedCertificates;

        private CacheKey(SslContextMode mode, @Nullable TlsKeyPair tlsKeyPair,
                         List<X509Certificate> trustedCertificates) {
            this.mode = mode;
            this.tlsKeyPair = tlsKeyPair;
            this.trustedCertificates = trustedCertificates;
        }

        SslContextMode mode() {
            return mode;
        }

        @Nullable
        TlsKeyPair tlsKeyPair() {
            return tlsKeyPair;
        }

        public List<X509Certificate> trustedCertificates() {
            return trustedCertificates;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey)) {
                return false;
            }
            final CacheKey that = (CacheKey) o;
            return mode == that.mode &&
                   Objects.equals(tlsKeyPair, that.tlsKeyPair) &&
                   trustedCertificates.equals(that.trustedCertificates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, tlsKeyPair, trustedCertificates);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .omitNullValues()
                              .add("mode", mode)
                              .add("tlsKeyPair", tlsKeyPair)
                              .add("trustedCertificates", trustedCertificates)
                              .toString();
        }
    }

    private static final class SslContextHolder {
        private final SslContext sslContext;
        @Nullable
        private final CloseableMeterBinder meterBinder;
        private long refCnt;

        SslContextHolder(SslContext sslContext, @Nullable CloseableMeterBinder meterBinder) {
            this.sslContext = sslContext;
            this.meterBinder = meterBinder;
        }

        SslContext sslContext() {
            return sslContext;
        }

        void retain() {
            refCnt++;
        }

        boolean release() {
            refCnt--;
            assert refCnt >= 0 : "refCount: " + refCnt;
            return refCnt == 0;
        }

        void destroy() {
            if (meterBinder != null) {
                meterBinder.close();
            }
        }
    }
}
