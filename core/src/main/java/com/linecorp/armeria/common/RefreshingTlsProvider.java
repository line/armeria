/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class RefreshingTlsProvider implements TlsProvider {

    private static final Logger logger = LoggerFactory.getLogger(RefreshingTlsProvider.class);

    private final Supplier<TlsKeyPair> keyPairSupplier;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> scheduledFuture;
    private final List<X509Certificate> certificates;

    private volatile TlsKeyPair keyPair;

    RefreshingTlsProvider(Supplier<TlsKeyPair> keyPairSupplier,
                          List<X509Certificate> certificates,
                          @Nullable Consumer<TlsKeyPair> onKeyPairUpdated,
                          Duration interval,
                          ScheduledExecutorService executor) {
        this.keyPairSupplier = keyPairSupplier;
        this.certificates = certificates;
        this.interval = interval;
        this.executor = executor;

        keyPair = keyPairSupplier.get();
        requireNonNull(keyPair, "keyPairSupplier returned null");
        scheduledFuture = executor.scheduleAtFixedRate(() -> {
            try {
                final TlsKeyPair newKeyPair = keyPairSupplier.get();
                requireNonNull(newKeyPair, "keyPairSupplier returned null");
                if (!Objects.equals(keyPair, newKeyPair)) {
                    keyPair = newKeyPair;
                    if (onKeyPairUpdated != null) {
                        try {
                            onKeyPairUpdated.accept(newKeyPair);
                        } catch (Throwable t) {
                            logger.warn("Failed to notify TLS key pair update listeners", t);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("Failed to refresh a new TlsKeyPair:", t);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public TlsKeyPair keyPair(String hostname) {
        return keyPair;
    }

    @Override
    public List<X509Certificate> trustedCertificates(String hostname) {
        return certificates;
    }

    @Override
    public void close() {
        scheduledFuture.cancel(true);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("keyPairSupplier", keyPairSupplier)
                          .add("interval", interval)
                          .add("executor", executor)
                          .add("keyPair", keyPair)
                          .toString();
    }
}
