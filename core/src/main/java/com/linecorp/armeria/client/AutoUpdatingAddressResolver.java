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
import static com.linecorp.armeria.internal.dns.DnsUtil.extractAddressBytes;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.internal.dns.DefaultDnsNameResolver;
import com.linecorp.armeria.internal.dns.DnsQuestionWithoutTrailingDot;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

class AutoUpdatingAddressResolver extends AbstractAddressResolver<InetSocketAddress> {

    private static final Logger logger = LoggerFactory.getLogger(AutoUpdatingAddressResolver.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<CacheEntry, ScheduledFuture> futureUpdater =
            AtomicReferenceFieldUpdater.newUpdater(CacheEntry.class, ScheduledFuture.class,
                                                   "cacheUpdatingScheduledFuture");

    /**
     * A {@link ScheduledFuture} which is set in {@link CacheEntry} when the
     * {@link AutoUpdatingAddressResolver} is closed by replacing and cancelling previously scheduled automatic
     * DNS cache updating.
     */
    @VisibleForTesting
    @SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
    static final ScheduledFuture<?> closed = new ScheduledFuture<Object>() {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MIN_VALUE;
        }

        @Override
        public int compareTo(Delayed o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    };

    private final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache;
    private final DefaultDnsNameResolver resolver;
    private final List<DnsRecordType> dnsRecordTypes;
    private final int minTtl;
    private final int maxTtl;
    private final Backoff autoUpdateBackoff;
    private final long autoUpdateTimeoutMillis;

    private volatile boolean resolverClosed;

    AutoUpdatingAddressResolver(EventLoop eventLoop,
                                ConcurrentMap<String, CompletableFuture<CacheEntry>> cache,
                                DefaultDnsNameResolver resolver, List<DnsRecordType> dnsRecordTypes,
                                int minTtl, int maxTtl, Backoff autoUpdateBackoff,
                                long autoUpdateTimeoutMillis) {
        super(eventLoop);
        this.cache = cache;
        this.resolver = resolver;
        this.dnsRecordTypes = dnsRecordTypes;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        this.autoUpdateBackoff = autoUpdateBackoff;
        this.autoUpdateTimeoutMillis = autoUpdateTimeoutMillis;
    }

    @Override
    protected boolean doIsResolved(InetSocketAddress address) {
        return !requireNonNull(address, "address").isUnresolved();
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
        final String hostname = unresolvedAddress.getHostString();
        final int port = unresolvedAddress.getPort();
        final CompletableFuture<CacheEntry> entryFuture = cache.get(hostname);
        if (entryFuture != null) {
            handle(entryFuture, promise, port);
            return;
        }

        final CompletableFuture<CacheEntry> result = new CompletableFuture<>();
        final CompletableFuture<CacheEntry> previous = cache.putIfAbsent(hostname, result);
        if (previous != null) {
            handle(previous, promise, port);
            return;
        }

        final List<DnsQuestion> questions =
                dnsRecordTypes.stream()
                              .map(type -> DnsQuestionWithoutTrailingDot.of(hostname, type))
                              .collect(toImmutableList());
        sendQueries(questions, hostname, result);
        result.handle((entry, cause) -> {
            if (cause != null) {
                cache.remove(hostname);
                promise.tryFailure(cause);
                return null;
            }
            promise.trySuccess(new InetSocketAddress(entry.address(), port));
            return null;
        });
    }

    private static void handle(CompletableFuture<CacheEntry> future, Promise<InetSocketAddress> promise,
                               int port) {
        future.handle((entry, cause) -> {
            if (cause != null) {
                promise.tryFailure(cause);
                return null;
            }
            entry.incrementCacheHit();
            promise.trySuccess(new InetSocketAddress(entry.address(), port));
            return null;
        });
    }

    private void sendQueries(List<DnsQuestion> questions, String hostname,
                             CompletableFuture<CacheEntry> result) {
        final Future<List<DnsRecord>> recordsFuture = resolver.sendQueries(questions, hostname);
        recordsFuture.addListener(f -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }

            @SuppressWarnings("unchecked")
            final List<DnsRecord> records = (List<DnsRecord>) f.getNow();
            InetAddress inetAddress = null;
            long ttlMillis = -1;
            try {
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
                        result.completeExceptionally(
                                new IllegalArgumentException("Invalid address: " + hostname, e));
                        return;
                    }
                }
            } finally {
                records.forEach(ReferenceCountUtil::safeRelease);
            }

            if (inetAddress == null) {
                result.completeExceptionally(
                        new UnknownHostException("failed to receive DNS records for " + hostname));
                return;
            }
            final CacheEntry entry = new CacheEntry(inetAddress, ttlMillis, questions);
            result.complete(entry);
            entry.scheduleCacheUpdate(ttlMillis);
        });
    }

    @Override
    protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        resolverClosed = true;
        resolver.close();
    }

    final class CacheEntry implements Runnable {

        private final InetAddress address;
        private final long ttlMillis;
        private final List<DnsQuestion> questions;
        private final long deadlineNanos;

        /**
         * No need to be volatile because updated only by the {@link #executor()}.
         */
        private int numAttemptsSoFar = 1;

        private volatile int cacheHits;

        @Nullable
        volatile ScheduledFuture<?> cacheUpdatingScheduledFuture;

        CacheEntry(InetAddress address, long ttlMillis, List<DnsQuestion> questions) {
            this.address = address;
            this.ttlMillis = ttlMillis;
            this.questions = questions;

            if (autoUpdateTimeoutMillis <= 0 || autoUpdateTimeoutMillis == Long.MAX_VALUE) {
                deadlineNanos = 0;
            } else {
                deadlineNanos = System.nanoTime() +
                                TimeUnit.MILLISECONDS.toNanos(ttlMillis + autoUpdateTimeoutMillis);
            }
        }

        void incrementCacheHit() {
            cacheHits++;
        }

        InetAddress address() {
            return address;
        }

        long ttlMillis() {
            return ttlMillis;
        }

        void scheduleCacheUpdate(long nextDelayMillis) {
            for (;;) {
                if (resolverClosed) {
                    return;
                }
                final ScheduledFuture<?> oldFuture = futureUpdater.get(this);
                if (oldFuture == closed) {
                    return;
                }

                final ScheduledFuture<?> newFuture =
                        executor().schedule(this, nextDelayMillis, TimeUnit.MILLISECONDS);
                if (futureUpdater.compareAndSet(this, oldFuture, newFuture)) {
                    return;
                } else {
                    newFuture.cancel(true);
                }
            }
        }

        void clear() {
            assert resolverClosed;
            final ScheduledFuture<?> future = futureUpdater.getAndSet(this, closed);
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void run() {
            if (resolverClosed) {
                return;
            }

            final String hostName = address.getHostName();
            if (cacheHits <= 0) { // Should we increase 0 to N?
                cache.remove(hostName);
                return;
            }

            final CompletableFuture<CacheEntry> result = new CompletableFuture<>();
            sendQueries(questions, hostName, result);
            result.handle((entry, cause) -> {
                if (resolverClosed) {
                    return null;
                }

                if (cause != null) {
                    if (deadlineNanos == 0) {
                        final long nextDelayMillis = autoUpdateBackoff.nextDelayMillis(numAttemptsSoFar++);
                        scheduleCacheUpdate(nextDelayMillis);
                        return null;
                    }
                    final long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    if (timeoutMillis <= 0) {
                        cache.remove(hostName);
                        return null;
                    }
                    final long nextDelayMillis = autoUpdateBackoff.nextDelayMillis(numAttemptsSoFar++);
                    scheduleCacheUpdate(Math.min(timeoutMillis, nextDelayMillis));
                    return null;
                }

                // Got the response successfully so reset the state.
                cacheHits = 0;
                numAttemptsSoFar = 1;

                if (entry.address().equals(address) && entry.ttlMillis() == ttlMillis) {
                    scheduleCacheUpdate(ttlMillis);
                } else {
                    // Replace the old entry with the new one.
                    cache.put(hostName, result);
                    entry.scheduleCacheUpdate(entry.ttlMillis());
                }
                return null;
            });
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("address", address)
                              .add("ttlMillis", ttlMillis)
                              .add("questions", questions)
                              .add("cacheHits", cacheHits)
                              .toString();
        }
    }
}
