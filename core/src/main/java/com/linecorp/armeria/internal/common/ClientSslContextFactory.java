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

import com.google.common.annotations.VisibleForTesting;
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

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;

public final class ClientSslContextFactory {

    private static final MeterIdPrefix SERVER_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.server");
    private static final MeterIdPrefix CLIENT_METER_ID_PREFIX =
            new MeterIdPrefix("armeria.client");

    private final Map<AbstractTlsSpec, SslContextHolder> cache = new HashMap<>();
    private final Map<SslContext, AbstractTlsSpec> reverseCache = new HashMap<>();

    private final MeterRegistry meterRegistry;
    private final boolean allowUnsafeCiphers;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    private final ReentrantShortLock lock = new ReentrantShortLock();

    public ClientSslContextFactory(MeterRegistry meterRegistry) {
        this(null, meterRegistry);
    }

    public ClientSslContextFactory(@Nullable MeterIdPrefix meterIdPrefix, MeterRegistry meterRegistry) {
        this.meterIdPrefix = meterIdPrefix;
        this.meterRegistry = meterRegistry;
        allowUnsafeCiphers = false;
    }

    public ClientSslContextFactory(@Nullable MeterIdPrefix meterIdPrefix, MeterRegistry meterRegistry,
                                   boolean allowUnsafeCiphers) {
        this.meterIdPrefix = meterIdPrefix;
        this.meterRegistry = meterRegistry;
        this.allowUnsafeCiphers = allowUnsafeCiphers;
    }

    public SslContext getOrCreate(ClientTlsSpec clientTlsSpec) {
        lock.lock();
        try {
            final SslContextHolder contextHolder =
                    cache.computeIfAbsent(clientTlsSpec, unused -> {
                        final SslContext sslContext =
                                SslContextUtil.toSslContext(clientTlsSpec, allowUnsafeCiphers);
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
        CloseableMeterBinder meterBinder = null;
        final ImmutableList.Builder<X509Certificate> certsBuilder = ImmutableList.builder();
        final TlsKeyPair keyPair = tlsSpec.tlsKeyPair();
        if (keyPair != null) {
            certsBuilder.addAll(keyPair.certificateChain());
        }
        final List<X509Certificate> certs =
                certsBuilder.addAll(tlsSpec.trustedCertificates()).build();
        if (!certs.isEmpty()) {
            final MeterIdPrefix meterIdPrefix = meterIdPrefix(tlsSpec);
            meterBinder = MoreMeterBinders.certificateMetrics(certs, meterIdPrefix);
            meterBinder.bindTo(meterRegistry);
        }
        return new SslContextHolder(sslContext, meterBinder);
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
        return meterIdPrefix;
    }

    @VisibleForTesting
    public int numCachedContexts() {
        return cache.size();
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
            ReferenceCountUtil.release(sslContext);
        }
    }
}
