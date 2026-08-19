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

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.CloseableMeterBinder;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.server.ServerTlsSpec;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;

public final class SslContextFactory {

    private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.server");
    private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.client");

    private final Map<AbstractTlsSpec, SslContextHolder> cache = new HashMap<>();
    private final Map<SslContext, AbstractTlsSpec> reverseCache = new HashMap<>();
    // Certificate metrics are shared across all cached contexts that register the exact same meters
    // (same certificates and meter ID prefix). Without this, two contexts that differ only in an
    // attribute irrelevant to the meter identity - e.g. ALPN protocols - would each bind an identical
    // set of gauges, producing "Gauge already registered" warnings and, worse, unregistering the
    // shared gauges as soon as the first of them is released. See
    // https://github.com/line/armeria/issues/6734
    private final Map<CertificateMetricsKey, CertificateMetricsHolder> certificateMetrics =
            new HashMap<>();

    private final MeterRegistry meterRegistry;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    private final ReentrantShortLock lock = new ReentrantShortLock();

    public SslContextFactory(MeterRegistry meterRegistry) {
        this(null, meterRegistry);
    }

    public SslContextFactory(@Nullable MeterIdPrefix meterIdPrefix, MeterRegistry meterRegistry) {
        this.meterIdPrefix = meterIdPrefix;
        this.meterRegistry = meterRegistry;
    }

    public SslContext getOrCreate(ServerTlsSpec serverTlsSpec) {
        lock.lock();
        try {
            final SslContextHolder contextHolder =
                    cache.computeIfAbsent(serverTlsSpec, unused -> {
                        final SslContext sslContext =
                                SslContextUtil.toSslContext(serverTlsSpec);
                        return toContextHolder(serverTlsSpec, sslContext);
                    });
            contextHolder.retain();
            reverseCache.putIfAbsent(contextHolder.sslContext(), serverTlsSpec);
            return contextHolder.sslContext();
        } finally {
            lock.unlock();
        }
    }

    public SslContext getOrCreate(ClientTlsSpec clientTlsSpec) {
        lock.lock();
        try {
            final SslContextHolder contextHolder =
                    cache.computeIfAbsent(clientTlsSpec, unused -> {
                        final SslContext sslContext =
                                SslContextUtil.toSslContext(clientTlsSpec);
                        return toContextHolder(clientTlsSpec, sslContext);
                    });
            contextHolder.retain();
            reverseCache.putIfAbsent(contextHolder.sslContext(), clientTlsSpec);
            return contextHolder.sslContext();
        } finally {
            lock.unlock();
        }
    }

    private SslContextHolder toContextHolder(AbstractTlsSpec tlsSpec, SslContext sslContext) {
        CertificateMetricsKey meterKey = null;
        final ImmutableList.Builder<X509Certificate> certsBuilder = ImmutableList.builder();
        final TlsKeyPair keyPair = tlsSpec.tlsKeyPair();
        if (keyPair != null) {
            certsBuilder.addAll(keyPair.certificateChain());
        }
        final List<X509Certificate> certs =
                certsBuilder.addAll(tlsSpec.trustedCertificates()).build();
        if (!certs.isEmpty()) {
            final MeterIdPrefix meterIdPrefix = meterIdPrefix(tlsSpec);
            meterKey = new CertificateMetricsKey(certs, meterIdPrefix);
            // Bind the certificate metrics only once per unique meter identity, and retain a
            // reference so they are unbound only after the last context using them is released.
            final CertificateMetricsHolder metricsHolder =
                    certificateMetrics.computeIfAbsent(meterKey, key -> {
                        final CloseableMeterBinder meterBinder =
                                MoreMeterBinders.certificateMetrics(key.certificates, key.meterIdPrefix);
                        meterBinder.bindTo(meterRegistry);
                        return new CertificateMetricsHolder(meterBinder);
                    });
            metricsHolder.retain();
        }
        return new SslContextHolder(sslContext, meterKey);
    }

    private void releaseCertificateMetrics(CertificateMetricsKey meterKey) {
        final CertificateMetricsHolder metricsHolder = certificateMetrics.get(meterKey);
        assert metricsHolder != null : "certificate metrics not found for: " + meterKey;
        if (metricsHolder.release()) {
            certificateMetrics.remove(meterKey);
            metricsHolder.meterBinder.close();
        }
    }

    public void release(SslContext sslContext) {
        lock.lock();
        try {
            final AbstractTlsSpec cacheKey = reverseCache.get(sslContext);
            final SslContextHolder contextHolder = cache.get(cacheKey);
            assert contextHolder != null : "sslContext not found in the cache: " + sslContext;

            if (contextHolder.release()) {
                final SslContextHolder removed = cache.remove(cacheKey);
                assert removed == contextHolder;
                reverseCache.remove(sslContext);
                contextHolder.destroy();
                final CertificateMetricsKey meterKey = contextHolder.meterKey();
                if (meterKey != null) {
                    releaseCertificateMetrics(meterKey);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private MeterIdPrefix meterIdPrefix(AbstractTlsSpec tlsSpec) {
        MeterIdPrefix meterIdPrefix = this.meterIdPrefix;
        if (meterIdPrefix == null) {
            if (tlsSpec.isServer()) {
                meterIdPrefix = SERVER_METER_ID_PREFIX;
            } else {
                meterIdPrefix = CLIENT_METER_ID_PREFIX;
            }
        }
        if (tlsSpec.isServer()) {
            meterIdPrefix = meterIdPrefix.withTags("hostname.pattern",
                                                   ((ServerTlsSpec) tlsSpec).hostnamePattern());
        }
        return meterIdPrefix;
    }

    @VisibleForTesting
    public int numCachedContexts() {
        return cache.size();
    }

    private static final class SslContextHolder {
        private final SslContext sslContext;
        @Nullable
        private final CertificateMetricsKey meterKey;
        private long refCnt;

        SslContextHolder(SslContext sslContext, @Nullable CertificateMetricsKey meterKey) {
            this.sslContext = sslContext;
            this.meterKey = meterKey;
        }

        SslContext sslContext() {
            return sslContext;
        }

        @Nullable
        CertificateMetricsKey meterKey() {
            return meterKey;
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
            ReferenceCountUtil.release(sslContext);
        }
    }

    /**
     * Identifies the certificate metrics registered for a set of certificates under a
     * {@link MeterIdPrefix}. Two {@link AbstractTlsSpec}s that yield an equal key register an
     * identical set of gauges, so they must share a single {@link CertificateMetricsHolder}.
     */
    private static final class CertificateMetricsKey {
        private final List<X509Certificate> certificates;
        private final MeterIdPrefix meterIdPrefix;

        CertificateMetricsKey(List<X509Certificate> certificates, MeterIdPrefix meterIdPrefix) {
            this.certificates = certificates;
            this.meterIdPrefix = meterIdPrefix;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CertificateMetricsKey)) {
                return false;
            }
            final CertificateMetricsKey that = (CertificateMetricsKey) o;
            return certificates.equals(that.certificates) &&
                   meterIdPrefix.equals(that.meterIdPrefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(certificates, meterIdPrefix);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("certificates", certificates)
                              .add("meterIdPrefix", meterIdPrefix)
                              .toString();
        }
    }

    /**
     * A reference-counted {@link CloseableMeterBinder} shared by all cached contexts that register the
     * same certificate metrics.
     */
    private static final class CertificateMetricsHolder {
        private final CloseableMeterBinder meterBinder;
        private long refCnt;

        CertificateMetricsHolder(CloseableMeterBinder meterBinder) {
            this.meterBinder = meterBinder;
        }

        void retain() {
            refCnt++;
        }

        boolean release() {
            refCnt--;
            assert refCnt >= 0 : "refCount: " + refCnt;
            return refCnt == 0;
        }
    }
}
