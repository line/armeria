/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.client.dns;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.dns.DefaultDnsCache.CacheEntry;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

final class CachingDnsResolver extends AbstractUnwrappable<DnsResolver> implements DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(CachingDnsResolver.class);

    private final Map<DnsQuestion, CompletableFuture<List<DnsRecord>>> inflightRequests =
            new ConcurrentHashMap<>();

    private final DnsCache dnsCache;

    CachingDnsResolver(DnsResolver delegate, DnsCache dnsCache) {
        super(delegate);
        this.dnsCache = dnsCache;
    }

    @Override
    public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
        requireNonNull(question, "question");
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        try {
            final List<DnsRecord> dnsRecords = cachedValue(ctx, question);
            if (dnsRecords != null) {
                future.complete(dnsRecords);
            } else {
                return resolve0(ctx, question);
            }
        } catch (UnknownHostException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private CompletableFuture<List<DnsRecord>> resolve0(DnsQuestionContext ctx, DnsQuestion question) {
        final CompletableFuture<List<DnsRecord>> future =
                inflightRequests.computeIfAbsent(question, key -> {
                    try {
                        // Re-check the DNS cache to avoid duplicate requests.
                        // Because a request could be computed right after the in-flight request is removed.
                        final List<DnsRecord> dnsRecords = cachedValue(ctx, key);
                        if (dnsRecords != null) {
                            return CompletableFuture.completedFuture(dnsRecords);
                        }
                    } catch (UnknownHostException e) {
                        return CompletableFutures.exceptionallyCompletedFuture(e);
                    }

                    return unwrap().resolve(ctx, key).handle((records, cause) -> {
                        if (records != null) {
                            final List<DnsRecord> copied = records.stream()
                                                                  .map(ByteArrayDnsRecord::copyOf)
                                                                  .collect(toImmutableList());

                            logger.debug("[{}] Caching DNS records: {}", question.name(), copied);
                            dnsCache.cache(key, copied);
                            return copied;
                        } else {
                            cause = Exceptions.peel(cause);
                            if (cause instanceof UnknownHostException) {
                                logger.debug("[{}] Caching a failed DNS query: {}, cause: {}",
                                             question.name(), question, cause.getMessage());
                                dnsCache.cache(key, (UnknownHostException) cause);
                            }
                            return Exceptions.throwUnsafely(cause);
                        }
                    });
                });

        // Remove the cached in-flight request.
        future.handle((unused0, unused1) -> inflightRequests.remove(question));
        return future;
    }

    @Nullable
    private List<DnsRecord> cachedValue(DnsQuestionContext ctx, DnsQuestion question)
            throws UnknownHostException {
        if (!ctx.isRefreshing()) {
            return dnsCache.get(question);
        }

        if (!(dnsCache instanceof DefaultDnsCache)) {
            return null;
        }

        final CacheEntry entry = ((DefaultDnsCache) dnsCache).getEntry(question);
        if (entry == null) {
            return null;
        }

        final long elapsed = NANOSECONDS.toSeconds(System.nanoTime() - entry.creationTimeNanos());
        if (elapsed < MILLISECONDS.toSeconds(ctx.refreshIntervalMillis())) {
            // The TTL of the cached entry is still new compared to the refresh interval.
            // The cached entry will be refreshed in the next iteration.
            final List<DnsRecord> records = entry.records();
            if (records != null) {
                return records;
            } else {
                assert entry.cause() != null;
                throw entry.cause();
            }
        }

        return null;
    }

    @Override
    public void close() {
        unwrap().close();
    }
}
