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

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.DnsTimeoutException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.EventExecutor;

public final class DefaultDnsResolver implements SafeCloseable {

    private static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

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

        return new DefaultDnsResolver(resolver, eventLoop, resolvedAddressTypes, queryTimeoutMillis);
    }

    private final DnsResolver delegate;
    private final EventExecutor executor;
    private final Comparator<DnsRecordType> preferredOrder;
    private final long queryTimeoutMillis;

    public DefaultDnsResolver(DnsResolver delegate, EventExecutor executor,
                              ResolvedAddressTypes resolvedAddressTypes, long queryTimeoutMillis) {
        this.delegate = delegate;
        this.executor = executor;
        if (resolvedAddressTypes == ResolvedAddressTypes.IPV6_PREFERRED) {
            preferredOrder = Ordering.explicit(DnsRecordType.AAAA, DnsRecordType.A);
        } else {
            preferredOrder = Ordering.explicit(DnsRecordType.A, DnsRecordType.AAAA);
        }
        this.queryTimeoutMillis = queryTimeoutMillis;
    }

    public CompletableFuture<List<DnsRecord>> resolve(List<DnsQuestion> questions, String logPrefix) {
        return resolve(questions, logPrefix, false);
    }

    public CompletableFuture<List<DnsRecord>> resolve(List<DnsQuestion> questions, String logPrefix,
                                                      boolean isRefreshing) {
        assert !questions.isEmpty();
        final DnsQuestionContext ctx =
                new DnsQuestionContext(executor, queryTimeoutMillis, isRefreshing);
        if (questions.size() == 1) {
            return resolveOne(ctx, questions.get(0));
        } else {
            return resolveAll(ctx, questions, logPrefix);
        }
    }

    private CompletableFuture<List<DnsRecord>> resolveOne(DnsQuestionContext ctx, DnsQuestion question) {
        final CompletableFuture<List<DnsRecord>> future = delegate.resolve(ctx, question);
        ctx.whenCancelled().handle((unused0, unused1) -> {
            if (!future.isDone()) {
                future.completeExceptionally(new DnsTimeoutException(
                        question + " is timed out after " + ctx.queryTimeoutMillis() + " milliseconds."));
            }
            return null;
        });
        return future;
    }

    @VisibleForTesting
    CompletableFuture<List<DnsRecord>> resolveAll(DnsQuestionContext ctx, List<DnsQuestion> questions,
                                                  String logPrefix) {

        final List<CompletableFuture<List<DnsRecord>>> results =
                questions.stream()
                         .map(question -> delegate.resolve(ctx, question))
                         .collect(toImmutableList());

        final CompletableFuture<List<DnsRecord>> future =
                CompletableFuture.allOf(results.toArray(EMPTY_FUTURES)).handle((unused0, unused1) -> {
                    final List<DnsRecord> records = new ArrayList<>();
                    List<Throwable> causes = null;
                    for (CompletableFuture<List<DnsRecord>> result : results) {
                        try {
                            records.addAll(result.get());
                        } catch (Throwable ex) {
                            if (records.isEmpty()) {
                                if (causes == null) {
                                    causes = new ArrayList<>();
                                }
                                causes.add(Exceptions.peel(ex));
                            }
                        }
                    }

                    if (!records.isEmpty()) {
                        if (records.size() > 1) {
                            records.sort(Comparator.comparing(DnsRecord::type, preferredOrder));
                        }
                        return Collections.unmodifiableList(records);
                    }

                    final Throwable cause;
                    if (causes == null) {
                        cause = new UnknownHostException("Failed to resolve: " + questions + " (empty result)");
                    } else {
                        cause = new UnknownHostException("Failed to resolve: " + questions);
                        for (Throwable c : causes) {
                            cause.addSuppressed(c);
                        }
                    }
                    return Exceptions.throwUnsafely(cause);
                });

        ctx.whenCancelled().handle((unused0, unused1) -> {
            if (!future.isDone()) {
                future.completeExceptionally(new DnsTimeoutException(
                        '[' + logPrefix + "] " + questions + " is timed out after " +
                        ctx.queryTimeoutMillis() + " milliseconds."));
            }
            return null;
        });

        return future;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
