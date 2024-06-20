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
import static com.linecorp.armeria.client.DnsResolverGroupBuilder.DEFAULT_AUTO_REFRESH_TIMEOUT_FUNCTION;
import static com.linecorp.armeria.internal.client.dns.DnsUtil.extractAddressBytes;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

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
import com.linecorp.armeria.internal.client.dns.DnsUtil;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

final class RefreshingAddressResolver
        extends AbstractAddressResolver<InetSocketAddress> implements DnsCacheListener {

    private static final Logger logger = LoggerFactory.getLogger(RefreshingAddressResolver.class);

    private final Cache<String, CacheEntry> addressResolverCache;
    private final DefaultDnsResolver resolver;
    private final List<DnsRecordType> dnsRecordTypes;
    private final int negativeTtl;
    @Nullable
    private final Backoff autoRefreshBackoff;
    @Nullable
    private final ToLongFunction<String> autoRefreshTimeoutFunction;
    private final boolean autoRefresh;

    private volatile boolean resolverClosed;

    RefreshingAddressResolver(EventLoop eventLoop, DefaultDnsResolver resolver,
                              List<DnsRecordType> dnsRecordTypes,
                              Cache<String, CacheEntry> addressResolverCache,
                              DnsCache dnsResolverCache, int negativeTtl,
                              boolean autoRefresh, @Nullable Backoff autoRefreshBackoff,
                              @Nullable ToLongFunction<String> autoRefreshTimeoutFunction) {
        super(eventLoop);
        this.addressResolverCache = addressResolverCache;
        this.resolver = resolver;
        this.dnsRecordTypes = dnsRecordTypes;
        this.negativeTtl = negativeTtl;
        this.autoRefresh = autoRefresh;
        if (autoRefresh) {
            assert autoRefreshBackoff != null;
            assert autoRefreshTimeoutFunction != null;
        }
        this.autoRefreshBackoff = autoRefreshBackoff;
        this.autoRefreshTimeoutFunction = autoRefreshTimeoutFunction;
        dnsResolverCache.addListener(this);
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
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            doResolve0(unresolvedAddress, promise);
        } else {
            executor.execute(() -> doResolve0(unresolvedAddress, promise));
        }
    }

    private void doResolve0(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise) {
        if (resolverClosed) {
            promise.tryFailure(new IllegalStateException("resolver is closed already."));
            return;
        }
        final String hostname = unresolvedAddress.getHostString();
        final int port = unresolvedAddress.getPort();
        final CacheEntry entry = addressResolverCache.getIfPresent(hostname);
        if (entry != null) {
            complete(promise, entry, port);
            return;
        }

        // Duplicate queries will be merged into the previous one by CachingDnsResolver.
        final CompletableFuture<CacheEntry> entryFuture = sendQuery(hostname);
        entryFuture.handle((entry0, unused) -> {
            if (entry0.cacheable()) {
                addressResolverCache.put(hostname, entry0);
            }
            complete(promise, entry0, port);
            return null;
        });
    }

    @Override
    protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    private void complete(Promise<InetSocketAddress> promise, CacheEntry entry, int port) {
        final Throwable cause = entry.cause();
        if (cause != null) {
            promise.tryFailure(cause);
        } else {
            promise.trySuccess(new InetSocketAddress(entry.address(), port));
        }
    }

    private CompletableFuture<CacheEntry> sendQuery(String hostname) {
        final List<DnsQuestion> questions =
                dnsRecordTypes.stream()
                              .map(type -> DnsQuestionWithoutTrailingDot.of(hostname, type))
                              .collect(toImmutableList());
        return sendQueries(questions, hostname, null);
    }

    private CompletableFuture<CacheEntry> sendQueries(List<DnsQuestion> questions, String hostname,
                                                      @Nullable Long creationTimeNanos) {
        return resolver.resolve(questions, hostname).handle((records, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                return new CacheEntry(hostname, null, questions, creationTimeNanos, cause);
            }

            InetAddress inetAddress = null;
            for (DnsRecord r : records) {
                final byte[] addrBytes = extractAddressBytes(r, logger, hostname);
                if (addrBytes == null) {
                    continue;
                }
                try {
                    inetAddress = InetAddress.getByAddress(hostname, addrBytes);
                    break;
                } catch (UnknownHostException e) {
                    // Should never reach here because we already validated it in extractAddressBytes.
                    return new CacheEntry(hostname, null, questions, creationTimeNanos,
                                          new IllegalArgumentException("Invalid address: " + hostname, e));
                }
            }

            if (inetAddress == null) {
                return new CacheEntry(hostname, null, questions, creationTimeNanos, new UnknownHostException(
                        "failed to receive DNS records for " + hostname));
            }

            return new CacheEntry(hostname, inetAddress, questions, creationTimeNanos, null);
        });
    }

    /**
     * Invoked when the resolutions of {@link DnsQuestion} cached by {@code CachingDnsResolver} is removed.
     * Resends the {@link DnsQuestion} to refresh the old {@link DnsRecord}s.
     * If the previous {@link DnsQuestion} was not completed with {@link CacheEntry#refreshable()},
     * the {@link DnsQuestion} is no more automatically refreshed.
     */
    @Override
    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                          @Nullable UnknownHostException cause) {
        if (resolverClosed) {
            return;
        }

        final DnsRecordType type = question.type();
        if (!(type.equals(DnsRecordType.A) || type.equals(DnsRecordType.AAAA))) {
            // AddressResolver only uses A or AAAA.
            return;
        }

        assert question instanceof DnsQuestionWithoutTrailingDot;
        final DnsQuestionWithoutTrailingDot cast = (DnsQuestionWithoutTrailingDot) question;
        final String hostname = cast.originalName();

        if (!autoRefresh) {
            addressResolverCache.invalidate(hostname);
            return;
        }

        final CacheEntry entry = addressResolverCache.getIfPresent(hostname);
        if (entry != null) {
            if (entry.refreshable()) {
                // onRemoval is invoked by the executor of 'dnsResolverCache'.
                executor().execute(entry::refresh);
            } else {
                // Remove the old CacheEntry.
                addressResolverCache.invalidate(hostname);
            }
        }
    }

    @Override
    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                           @Nullable UnknownHostException cause) {
        if (resolverClosed) {
            return;
        }

        final DnsRecordType type = question.type();
        if (!(type.equals(DnsRecordType.A) || type.equals(DnsRecordType.AAAA))) {
            // AddressResolver only uses A or AAAA.
            return;
        }

        final DnsQuestionWithoutTrailingDot cast = (DnsQuestionWithoutTrailingDot) question;
        // The DnsCache is full. Don't schedule refreshing because it may cause another eviction.
        addressResolverCache.invalidate(cast.originalName());
    }

    /**
     * Closes all the resources allocated and used by this resolver.
     *
     * <p>Please note that this method does not clear the {@link CacheEntry} because the
     * {@link #addressResolverCache} is created by {@link RefreshingAddressResolverGroup} and shared across
     * from all {@link RefreshingAddressResolver}s. {@link CacheEntry} is cleared when
     * {@link RefreshingAddressResolverGroup#close()} is called.
     */
    @Override
    public void close() {
        resolverClosed = true;
        resolver.close();
    }

    final class CacheEntry {

        private final String hostname;
        @Nullable
        private final InetAddress address;
        private final List<DnsQuestion> questions;
        @Nullable
        private final Throwable cause;
        private final boolean cacheable;
        @Nullable
        private final ScheduledFuture<?> negativeCacheFuture;
        // A new CacheEntry should inherit the creation time of the original CacheEntry.
        private final long originalCreationTimeNanos;

        private boolean refreshing;
        @Nullable
        private ScheduledFuture<?> retryFuture;
        private int numAttemptsSoFar = 1;

        CacheEntry(String hostname, @Nullable InetAddress address, List<DnsQuestion> questions,
                   @Nullable Long originalCreationTimeNanos, @Nullable Throwable cause) {
            this.hostname = hostname;
            this.address = address;
            this.questions = questions;
            this.cause = cause;

            boolean cacheable = false;
            ScheduledFuture<?> negativeCacheFuture = null;
            if (address != null) {
                cacheable = true;
            } else if (negativeTtl > 0 && cause instanceof UnknownHostException) {
                // TODO(minwoox): In Netty, DnsNameResolver only caches if the failure was not because of an
                //                IO error / timeout that was caused by the query itself.
                //                To figure that out, we need to check the cause of the UnknownHostException.
                //                If it's null, then we can cache the cause. However, this is very fragile
                //                because Netty can change the behavior while we are not noticing that.
                //                So sending a PR to upstream would be the best solution.
                final UnknownHostException unknownHostException = (UnknownHostException) cause;
                cacheable = !DnsUtil.isDnsQueryTimedOut(unknownHostException.getCause());

                if (cacheable) {
                    negativeCacheFuture = executor().schedule(() -> addressResolverCache.invalidate(hostname),
                                                              negativeTtl, TimeUnit.SECONDS);
                }
            }
            this.cacheable = cacheable;
            this.negativeCacheFuture = negativeCacheFuture;
            if (originalCreationTimeNanos != null) {
                this.originalCreationTimeNanos = originalCreationTimeNanos;
            } else {
                if (autoRefreshTimeoutFunction == DEFAULT_AUTO_REFRESH_TIMEOUT_FUNCTION) {
                    this.originalCreationTimeNanos = 0;
                } else {
                    this.originalCreationTimeNanos = System.nanoTime();
                }
            }
        }

        @Nullable
        InetAddress address() {
            return address;
        }

        @Nullable
        Throwable cause() {
            return cause;
        }

        void clear() {
            executor().execute(() -> {
                if (retryFuture != null) {
                    retryFuture.cancel(false);
                }
                if (negativeCacheFuture != null) {
                    negativeCacheFuture.cancel(false);
                }
            });
        }

        void refresh() {
            if (resolverClosed) {
                return;
            }

            if (refreshing) {
                return;
            }
            refreshing = true;

            final String hostname = address.getHostName();
            // 'sendQueries()' always successfully completes.
            sendQueries(questions, hostname, originalCreationTimeNanos).thenAccept(entry -> {
                if (executor().inEventLoop()) {
                    maybeUpdate(hostname, entry);
                } else {
                    executor().execute(() -> maybeUpdate(hostname, entry));
                }
            });
        }

        @Nullable
        private Object maybeUpdate(String hostname, CacheEntry entry) {
            if (resolverClosed) {
                return null;
            }
            refreshing = false;

            final Throwable cause = entry.cause();
            if (cause != null) {
                final long nextDelayMillis = autoRefreshBackoff.nextDelayMillis(numAttemptsSoFar++);

                if (nextDelayMillis < 0) {
                    addressResolverCache.invalidate(hostname);
                } else {
                    final ScheduledFuture<?> retryFuture = this.retryFuture;
                    if (retryFuture != null) {
                        retryFuture.cancel(false);
                    }
                    this.retryFuture = executor().schedule(() -> {
                        if (refreshable()) {
                            refresh();
                        } else {
                            addressResolverCache.invalidate(hostname);
                        }
                    }, nextDelayMillis, MILLISECONDS);
                }
                return null;
            }

            // Got the response successfully so reset the state.
            numAttemptsSoFar = 1;
            addressResolverCache.put(hostname, entry);
            return null;
        }

        boolean refreshable() {
            if (address == null) {
                return false;
            }

            if (autoRefreshTimeoutFunction == DEFAULT_AUTO_REFRESH_TIMEOUT_FUNCTION) {
                return true;
            }

            try {
                final long timeoutMillis = autoRefreshTimeoutFunction.applyAsLong(hostname);
                if (timeoutMillis == Long.MAX_VALUE) {
                    return true;
                }
                if (timeoutMillis <= 0) {
                    return false;
                }

                final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() -
                                                                         originalCreationTimeNanos);
                return elapsedMillis < timeoutMillis;
            } catch (Exception ex) {
                logger.warn("Unexpected exception while invoking 'autoRefreshTimeoutFunction.applyAsLong({})'",
                            hostname, ex);
                return false;
            }
        }

        boolean cacheable() {
            return cacheable;
        }

        @VisibleForTesting
        long originalCreationTimeNanos() {
            return originalCreationTimeNanos;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("hostname", hostname)
                              .add("address", address)
                              .add("questions", questions)
                              .add("cause", cause)
                              .add("cacheable", cacheable)
                              .add("numAttemptsSoFar", numAttemptsSoFar)
                              .add("originalCreationTimeNanos", originalCreationTimeNanos)
                              .toString();
        }
    }
}
