/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.client.dns.DnsUtil.extractAddressBytes;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsQuestionWithoutTrailingDot;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.util.concurrent.Promise;

final class RefreshingAddressResolver extends AbstractAddressResolver<InetSocketAddress> {

    private static final Logger logger = LoggerFactory.getLogger(RefreshingAddressResolver.class);

    private final Cache<String, CompletableFuture<CacheEntry>> cache;
    private final DefaultDnsResolver resolver;
    private final List<DnsRecordType> dnsRecordTypes;
    private final int minTtl;
    private final int maxTtl;
    private final int negativeTtl;
    private final Backoff refreshBackoff;

    private volatile boolean resolverClosed;

    RefreshingAddressResolver(EventLoop eventLoop, DefaultDnsResolver resolver,
                              List<DnsRecordType> dnsRecordTypes,
                              Cache<String, CompletableFuture<CacheEntry>> cache,
                              int minTtl, int maxTtl, int negativeTtl, Backoff refreshBackoff) {
        super(eventLoop);
        this.cache = cache;
        this.resolver = resolver;
        this.dnsRecordTypes = dnsRecordTypes;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        this.negativeTtl = negativeTtl;
        this.refreshBackoff = refreshBackoff;
    }

    @Override
    protected boolean doIsResolved(InetSocketAddress address) {
        return !requireNonNull(address, "address").isUnresolved();
    }

    private CompletableFuture<CacheEntry> loadingCache(InetSocketAddress unresolvedAddress,
                                                       Promise<InetSocketAddress> promise) {
        final String hostname = unresolvedAddress.getHostString();
        final int port = unresolvedAddress.getPort();
        final CompletableFuture<CacheEntry> result = new CompletableFuture<>();
        final List<DnsQuestion> questions =
                dnsRecordTypes.stream()
                              .map(type -> DnsQuestionWithoutTrailingDot.of(hostname, type))
                              .collect(toImmutableList());
        sendQueries(questions, hostname, result, false);
        result.handle((entry, unused) -> {
            final Throwable cause = entry.cause();
            if (cause != null) {
                if (entry.hasCacheableCause() && negativeTtl > 0) {
                    executor().schedule(() -> cache.invalidate(hostname), negativeTtl, TimeUnit.SECONDS);
                } else {
                    // cache.get() must not directly invoke cache.invalidate().
                    // It causes an infinity loop or an `IllegalStateException: Recurse update`.
                    // https://bugs.openjdk.java.net/browse/JDK-8074374
                    executor().execute(() -> cache.invalidate(hostname));
                }
                promise.tryFailure(cause);
                return null;
            }

            entry.scheduleRefresh(entry.ttlMillis());
            promise.trySuccess(new InetSocketAddress(entry.address(), port));
            return null;
        });
        return result;
    }

    @Override
    protected void doResolve(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise)
            throws Exception {
        requireNonNull(unresolvedAddress, "unresolvedAddress");
        requireNonNull(promise, "promise");
        if (resolverClosed) {
            promise.tryFailure(new IllegalStateException("resolver is closed already."));
            return;
        }
        final CompletableFuture<CacheEntry> entryFuture =
                cache.get(unresolvedAddress.getHostString(), s -> loadingCache(unresolvedAddress, promise));
        assert entryFuture != null; // loader does not return null.
        entryFuture.handle((entry, unused) -> {
            final Throwable cause = entry.cause();
            if (cause != null) {
                promise.tryFailure(cause);
                return null;
            }
            promise.trySuccess(new InetSocketAddress(entry.address(), unresolvedAddress.getPort()));
            return null;
        });
    }

    private void sendQueries(List<DnsQuestion> questions, String hostname,
                             CompletableFuture<CacheEntry> result, boolean isRefreshing) {
        resolver.resolve(questions, hostname, isRefreshing).handle((records, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                // TODO(minwoox): In Netty, DnsNameResolver only caches if the failure was not because of an
                //                IO error / timeout that was caused by the query itself.
                //                To figure that out, we need to check the cause of the UnknownHostException.
                //                If it's null, then we can cache the cause. However, this is very fragile
                //                because Netty can change the behavior while we are not noticing that.
                //                So sending a PR to upstream would be the best solution.
                final boolean hasCacheableCause;
                if (cause instanceof UnknownHostException) {
                    final UnknownHostException unknownHostException = (UnknownHostException) cause;
                    hasCacheableCause = unknownHostException.getCause() == null;
                } else {
                    hasCacheableCause = false;
                }
                result.complete(new CacheEntry(null, -1, questions, cause, hasCacheableCause));
                return null;
            }

            InetAddress inetAddress = null;
            long ttlMillis = -1;
            for (DnsRecord r : records) {
                final byte[] addrBytes = extractAddressBytes(r, logger, hostname);
                if (addrBytes == null) {
                    continue;
                }
                try {
                    inetAddress = InetAddress.getByAddress(hostname, addrBytes);
                    ttlMillis = TimeUnit.SECONDS.toMillis(
                            Math.max(Math.min(r.timeToLive(), maxTtl), minTtl));
                    break;
                } catch (UnknownHostException e) {
                    // Should never reach here because we already validated it in extractAddressBytes.
                    result.complete(new CacheEntry(null, -1, questions, new IllegalArgumentException(
                            "Invalid address: " + hostname, e), false));
                    return null;
                }
            }

            final CacheEntry cacheEntry;
            if (inetAddress == null) {
                cacheEntry = new CacheEntry(null, -1, questions, new UnknownHostException(
                        "failed to receive DNS records for " + hostname), true);
            } else {
                cacheEntry = new CacheEntry(inetAddress, ttlMillis, questions, null, false);
            }
            result.complete(cacheEntry);
            return null;
        });
    }

    @Override
    protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    /**
     * Closes all the resources allocated and used by this resolver.
     *
     * <p>Please note that this method does not clear the {@link CacheEntry} because the {@link #cache} is
     * created by {@link RefreshingAddressResolverGroup} and shared across from all
     * {@link RefreshingAddressResolver}s. {@link CacheEntry} is cleared when
     * {@link RefreshingAddressResolverGroup#close()} is called.
     */
    @Override
    public void close() {
        resolverClosed = true;
        resolver.close();
    }

    final class CacheEntry implements Runnable {

        @Nullable
        private final InetAddress address;
        private final long ttlMillis;
        private final List<DnsQuestion> questions;

        @Nullable
        private final Throwable cause;
        private final boolean hasCacheableCause;

        /**
         * No need to be volatile because updated only by the {@link #executor()}.
         */
        private int numAttemptsSoFar = 1;

        @VisibleForTesting
        @Nullable
        ScheduledFuture<?> refreshFuture;

        CacheEntry(@Nullable InetAddress address, long ttlMillis, List<DnsQuestion> questions,
                   @Nullable Throwable cause, boolean hasCacheableCause) {
            this.address = address;
            this.ttlMillis = ttlMillis;
            this.questions = questions;
            this.cause = cause;
            this.hasCacheableCause = hasCacheableCause;
        }

        @Nullable
        InetAddress address() {
            return address;
        }

        long ttlMillis() {
            return ttlMillis;
        }

        @Nullable
        Throwable cause() {
            return cause;
        }

        boolean hasCacheableCause() {
            return hasCacheableCause;
        }

        void scheduleRefresh(long nextDelayMillis) {
            if (resolverClosed) {
                return;
            }
            refreshFuture = executor().schedule(this, nextDelayMillis, TimeUnit.MILLISECONDS);
        }

        void clear() {
            if (executor().inEventLoop()) {
                clear0();
            } else {
                executor().execute(this::clear0);
            }
        }

        void clear0() {
            if (refreshFuture != null) {
                refreshFuture.cancel(false);
            }
        }

        @Override
        public void run() {
            if (resolverClosed) {
                return;
            }

            assert address != null;
            final String hostName = address.getHostName();

            final CompletableFuture<CacheEntry> result = new CompletableFuture<>();
            sendQueries(questions, hostName, result, true);
            result.handle((entry, unused) -> {
                if (resolverClosed) {
                    return null;
                }

                final Throwable cause = entry.cause();
                if (cause != null) {
                    final long nextDelayMillis = refreshBackoff.nextDelayMillis(numAttemptsSoFar++);
                    if (nextDelayMillis < 0) {
                        cache.invalidate(hostName);
                        return null;
                    }
                    scheduleRefresh(nextDelayMillis);
                    return null;
                }

                // Got the response successfully so reset the state.
                numAttemptsSoFar = 1;

                if (address.equals(entry.address()) && entry.ttlMillis() == ttlMillis) {
                    scheduleRefresh(ttlMillis);
                } else {
                    // Replace the old entry with the new one.
                    cache.put(hostName, result);
                    entry.scheduleRefresh(entry.ttlMillis());
                }
                return null;
            });
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("address", address)
                              .add("ttlMillis", ttlMillis)
                              .add("questions", questions)
                              .add("cause", cause)
                              .add("hasCacheableCause", hasCacheableCause)
                              .add("numAttemptsSoFar", numAttemptsSoFar)
                              .toString();
        }
    }
}
