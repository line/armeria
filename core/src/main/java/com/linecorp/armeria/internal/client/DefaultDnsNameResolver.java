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

package com.linecorp.armeria.internal.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Ordering;

import com.linecorp.armeria.client.DnsTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

public final class DefaultDnsNameResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDnsNameResolver.class);

    private static final Iterable<DnsRecord> EMPTY_ADDITIONALS = ImmutableList.of();

    @VisibleForTesting
    public final DnsNameResolver delegate;

    private final Comparator<DnsRecordType> preferredOrder;
    private final EventLoop eventLoop;
    private final long queryTimeoutMillis;

    public DefaultDnsNameResolver(DnsNameResolver delegate, EventLoop eventLoop, long queryTimeoutMillis) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        checkArgument(queryTimeoutMillis >= 0, "queryTimeoutMillis: %s (expected: >= 0)", queryTimeoutMillis);
        this.queryTimeoutMillis = queryTimeoutMillis;

        if (delegate.resolvedAddressTypes() == ResolvedAddressTypes.IPV6_PREFERRED) {
            preferredOrder = Ordering.explicit(DnsRecordType.AAAA, DnsRecordType.A);
        } else {
            preferredOrder = Ordering.explicit(DnsRecordType.A, DnsRecordType.AAAA);
        }
    }

    public Future<List<DnsRecord>> sendQueries(List<DnsQuestion> questions, String logPrefix) {
        requireNonNull(questions, "questions");
        requireNonNull(logPrefix, "logPrefix");
        final int numQuestions = questions.size();
        if (numQuestions == 1) {
            // Simple case of single query
            final DnsQuestion question = questions.get(0);
            logger.debug("[{}] Sending a DNS query: {}", logPrefix, question);
            final Promise<List<DnsRecord>> promise = eventLoop.newPromise();
            delegate.resolveAll(question, EMPTY_ADDITIONALS, promise);
            configureTimeout(questions, logPrefix, promise, ImmutableList.of(promise));
            return promise;
        }

        // Multiple queries
        logger.debug("[{}] Sending DNS queries: {}", logPrefix, questions);
        final Promise<List<DnsRecord>> aggregatedPromise = eventLoop.newPromise();
        final FutureListener<List<DnsRecord>> listener = new FutureListener<List<DnsRecord>>() {
            private final List<DnsRecord> records = new ArrayList<>();
            private int remaining = numQuestions;
            @Nullable
            private List<Throwable> causes;

            @Override
            public void operationComplete(Future<List<DnsRecord>> future) throws Exception {
                if (future.isSuccess()) {
                    final List<DnsRecord> records = future.getNow();
                    this.records.addAll(records);
                } else {
                    if (causes == null) {
                        causes = new ArrayList<>(numQuestions);
                    }
                    causes.add(future.cause());
                }

                if (--remaining > 0) {
                    return;
                }

                boolean notifiedRecords = false;
                try {
                    if (aggregatedPromise.isDone()) {
                        return;
                    }

                    if (!records.isEmpty()) {
                        if (records.size() > 1) {
                            records.sort(Comparator.comparing(DnsRecord::type, preferredOrder));
                        }
                        notifiedRecords = aggregatedPromise.trySuccess(records);
                    } else {
                        final Throwable aggregatedCause;
                        if (causes == null) {
                            aggregatedCause = new UnknownHostException("Failed to resolve: " + questions +
                                                                       " (empty result)");
                        } else {
                            aggregatedCause = new UnknownHostException("Failed to resolve: " + questions);
                            for (Throwable c : causes) {
                                aggregatedCause.addSuppressed(c);
                            }
                        }
                        aggregatedPromise.tryFailure(aggregatedCause);
                    }
                } finally {
                    if (!notifiedRecords) {
                        records.forEach(ReferenceCountUtil::safeRelease);
                    }
                }
            }
        };

        final Builder<Promise<List<DnsRecord>>> promises =
                ImmutableList.builderWithExpectedSize(questions.size());
        questions.forEach(q -> {
            final Promise<List<DnsRecord>> promise = eventLoop.newPromise();
            promises.add(promise);
            delegate.resolveAll(q, EMPTY_ADDITIONALS, promise);
            promise.addListener(listener);
        });
        configureTimeout(questions, logPrefix, aggregatedPromise, promises.build());
        return aggregatedPromise;
    }

    private void configureTimeout(List<DnsQuestion> questions, String logPrefix,
                                  Promise<List<DnsRecord>> result,
                                  List<Promise<List<DnsRecord>>> promises) {
        if (queryTimeoutMillis == 0) {
            return;
        }
        eventLoop.schedule(() -> {
            if (result.isDone()) {
                // Received a response before the query times out.
                return;
            }
            // Return DnsTimeoutException if we can cancel all of the queries.
            if (promises.stream().noneMatch(Promise::isDone)) {
                result.setFailure(new DnsTimeoutException(
                        '[' + logPrefix + "] " + questions + " are timed out after " +
                        queryTimeoutMillis + " milliseconds."));
            }
            promises.forEach(promise -> promise.cancel(true));
        }, queryTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void close() {
        delegate.close();
    }
}
