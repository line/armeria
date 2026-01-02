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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Server;

/**
 * Provides {@link TlsKeyPair}s for TLS handshakes.
 */
@UnstableApi
@FunctionalInterface
public interface TlsProvider extends AutoCloseable {

    /**
     * Returns a {@link TlsProvider} which always returns the specified {@link TlsKeyPair}.
     */
    static TlsProvider of(TlsKeyPair tlsKeyPair) {
        requireNonNull(tlsKeyPair, "tlsKeyPair");
        return builder().keyPair(tlsKeyPair).build();
    }

    /**
     * Returns a {@link TlsProvider} that periodically refreshes the {@link TlsKeyPair} based on the given
     * {@code interval}.
     *
     * <p>Note that the key pair supplier is also invoked during the initialization of the returned
     * {@link TlsProvider}, so this factory method should not be called from an event loop thread.
     */
    static TlsProvider ofScheduled(Supplier<TlsKeyPair> keyPairSupplier, Duration interval) {
        return ofScheduled(keyPairSupplier, interval, CommonPools.blockingTaskExecutor());
    }

    /**
     * Returns a {@link TlsProvider} that periodically refreshes the {@link TlsKeyPair} based on the given
     * {@code interval} using the specified {@link ScheduledExecutorService}.
     *
     * <p>Note that the key pair supplier is also invoked during the initialization of the returned
     * {@link TlsProvider}, so this factory method should not be called from an event loop thread.
     */
    static TlsProvider ofScheduled(Supplier<TlsKeyPair> keyPairSupplier, Duration interval,
                                   ScheduledExecutorService executor) {
        return ofScheduled(keyPairSupplier, ImmutableList.of(), null, interval, executor);
    }

    /**
     * Returns a {@link TlsProvider} that periodically refreshes the {@link TlsKeyPair} based on the given
     * {@code interval} using the specified {@link ScheduledExecutorService}.
     *
     * <p>Note that the key pair supplier is also invoked during the initialization of the returned
     * {@link TlsProvider}, so this factory method should not be called from an event loop thread.
     *
     * @param keyPairSupplier the supplier of {@link TlsKeyPair} to be used for TLS handshakes.
     * @param certificates the trusted {@link X509Certificate}s to be used for verifying the remote endpoint's
     *                     certificate.
     * @param onKeyPairUpdated a {@link Consumer} that is invoked when the {@link TlsKeyPair} is updated.
     * @param interval the interval at which the {@code keyPairSupplier} is invoked to refresh the
     *                 {@link TlsKeyPair}.
     * @param executor the {@link ScheduledExecutorService} that is used to schedule the periodic refresh.
     */
    static TlsProvider ofScheduled(Supplier<TlsKeyPair> keyPairSupplier,
                                   Iterable<? extends X509Certificate> certificates,
                                   @Nullable Consumer<TlsKeyPair> onKeyPairUpdated,
                                   Duration interval, ScheduledExecutorService executor) {
        requireNonNull(keyPairSupplier, "keyPairSupplier");
        requireNonNull(certificates, "certificates");
        requireNonNull(interval, "interval");
        requireNonNull(executor, "executor");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval: " + interval + " (expected: > 0)");
        }
        return new RefreshingTlsProvider(keyPairSupplier, ImmutableList.copyOf(certificates),
                                         onKeyPairUpdated, interval, executor);
    }

    /**
     * Returns a newly created {@link TlsProviderBuilder}.
     *
     * <p>Example usage:
     * <pre>{@code
     * TlsProvider
     *   .builder()
     *   // Set the default key pair.
     *   .keyPair(TlsKeyPair.of(...))
     *   // Set the key pair for "api.example.com".
     *   .keyPair("api.example.com", TlsKeyPair.of(...))
     *   // Set the key pair for "web.example.com".
     *   .keyPair("web.example.com", TlsKeyPair.of(...))
     *   .build();
     * }</pre>
     */
    static TlsProviderBuilder builder() {
        return new TlsProviderBuilder();
    }

    /**
     * Finds a {@link TlsKeyPair} for the specified {@code hostname}.
     *
     * <p>If no matching {@link TlsKeyPair} is found for a hostname, {@code "*"} will be specified to get the
     * default {@link TlsKeyPair}.
     * If no default {@link TlsKeyPair} is found, {@code null} will be returned.
     *
     * <p>Note that this operation is executed in an event loop thread, so it should not be blocked.
     */
    @Nullable
    TlsKeyPair keyPair(String hostname);

    /**
     * Returns trusted certificates for verifying the remote endpoint's certificate.
     *
     * <p>If no matching {@link X509Certificate}s are found for a hostname, {@code "*"} will be specified to get
     * the default {@link X509Certificate}s.
     * The system default will be used if this method returns null.
     *
     * <p>Note that this operation is executed in an event loop thread, so it should not be blocked.
     */
    @Nullable
    default List<X509Certificate> trustedCertificates(String hostname) {
        return null;
    }

    /**
     * Returns whether this {@link TlsProvider} should be automatically closed when the {@link Server} or
     * {@link ClientFactory} that this {@link TlsProvider} is associated with is closed.
     *
     * <p>This options is enabled by default.
     */
    default boolean autoClose() {
        return true;
    }

    /**
     * Closes this {@link TlsProvider} and releases any resources it holds.
     */
    @Override
    default void close() {
        // No-op by default.
        // Implementations can override this method to release resources.
    }
}
