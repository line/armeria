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

import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.DnsTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.EventExecutor;

public final class DefaultDnsResolver implements SafeCloseable {

    public static DefaultDnsResolver of(DnsNameResolver delegate, DnsCache dnsCache, EventExecutor eventLoop,
                                        List<String> searchDomains, int ndots, long queryTimeoutMillis,
                                        HostsFileEntriesResolver hostsFileEntriesResolver) {
        requireNonNull(delegate, "delegate");
        requireNonNull(dnsCache, "dnsCache");
        requireNonNull(eventLoop, "eventLoop");
        requireNonNull(searchDomains, "searchDomains");
        requireNonNull(hostsFileEntriesResolver, "hostsFileEntriesResolver");

        DnsResolver resolver = new DelegatingDnsResolver(delegate, eventLoop);
        resolver = new CachingDnsResolver(resolver, dnsCache);
        if (!searchDomains.isEmpty()) {
            resolver = new SearchDomainDnsResolver(resolver, searchDomains, ndots);
        }

        final ResolvedAddressTypes resolvedAddressTypes = delegate.resolvedAddressTypes();
        resolver = new HostsFileDnsResolver(resolver, hostsFileEntriesResolver, resolvedAddressTypes);

        return new DefaultDnsResolver(resolver, dnsCache, eventLoop, queryTimeoutMillis);
    }

    private final DnsResolver delegate;
    private final DnsCache dnsCache;
    private final EventExecutor executor;
    private final long queryTimeoutMillis;

    public DefaultDnsResolver(DnsResolver delegate, DnsCache dnsCache, EventExecutor executor,
                              long queryTimeoutMillis) {
        this.delegate = delegate;
        this.dnsCache = dnsCache;
        this.executor = executor;
        this.queryTimeoutMillis = queryTimeoutMillis;
    }

    public CompletableFuture<List<DnsRecord>> resolve(List<? extends DnsQuestion> questions, String logPrefix) {
        assert !questions.isEmpty();
        final DnsQuestionContext ctx = new DnsQuestionContext(executor, queryTimeoutMillis);
        if (questions.size() == 1) {
            return resolveOne(ctx, questions.get(0));
        } else {
            return resolveAll(ctx, questions, logPrefix);
        }
    }

    private CompletableFuture<List<DnsRecord>> resolveOne(DnsQuestionContext ctx, DnsQuestion question) {
        assert executor.inEventLoop();
        final CompletableFuture<List<DnsRecord>> future = delegate.resolve(ctx, question);
        ctx.whenCancelled().handle((unused0, unused1) -> {
            if (!future.isDone()) {
                future.completeExceptionally(new DnsTimeoutException(
                        question + " is timed out after " + ctx.queryTimeoutMillis() + " milliseconds."));
            }
            return null;
        });
        future.handle((unused0, unused1) -> {
            // Maybe cancel the timeout scheduler.
            ctx.cancel();
            return null;
        });
        return future;
    }

    @VisibleForTesting
    CompletableFuture<List<DnsRecord>> resolveAll(DnsQuestionContext ctx, List<? extends DnsQuestion> questions,
                                                  String logPrefix) {
        assert executor.inEventLoop();
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        final Object[] results = new Object[questions.size()];
        for (int i = 0; i < questions.size(); i++) {
            final int order = i;
            delegate.resolve(ctx, questions.get(i)).handle((records, cause) -> {
                assert executor.inEventLoop();
                maybeCompletePreferredRecords(future, questions, results, order, records, cause);
                return null;
            });
        }

        ctx.whenCancelled().handle((unused0, unused1) -> {
            if (!future.isDone()) {
                assert executor.inEventLoop();

                for (Object result : results) {
                    if (result instanceof List) {
                        // If a less preferred question is resolved first, use the value instead.
                        //noinspection unchecked
                        future.complete((List<DnsRecord>) result);
                        return null;
                    }
                }
                future.completeExceptionally(new DnsTimeoutException(
                        '[' + logPrefix + "] " + questions + " is timed out after " +
                        ctx.queryTimeoutMillis() + " milliseconds."));
            }
            return null;
        });

        return future;
    }

    @VisibleForTesting
    static void maybeCompletePreferredRecords(CompletableFuture<List<DnsRecord>> future,
                                              List<? extends DnsQuestion> questions,
                                              Object[] results, int order,
                                              @Nullable List<DnsRecord> records,
                                              @Nullable Throwable cause) {
        if (future.isDone()) {
            // The question was timed out.
            return;
        }

        if (cause != null) {
            results[order] = Exceptions.peel(cause);
        } else {
            results[order] = records;
        }

        for (Object result : results) {
            if (result == null) {
                // A highly preferred question hasn't finished yet.
                return;
            }

            if (result instanceof Throwable) {
                // Skip a failed question and look up low priority ones.
                continue;
            }

            // Found a successful result.
            assert result instanceof List;
            future.complete(Collections.unmodifiableList((List<DnsRecord>) result));
            return;
        }

        // All queries are failed.
        final UnknownHostException unknownHostException =
                new UnknownHostException("Failed to resolve: " + questions);
        for (Object result : results) {
            assert result instanceof Throwable;
            unknownHostException.addSuppressed((Throwable) result);
        }
        future.completeExceptionally(unknownHostException);
    }

    public DnsCache dnsCache() {
        return dnsCache;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
