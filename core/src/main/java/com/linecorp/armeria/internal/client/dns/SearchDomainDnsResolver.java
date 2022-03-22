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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

final class SearchDomainDnsResolver extends AbstractUnwrappable<DnsResolver> implements DnsResolver {

    private final List<String> searchDomains;
    private final int ndots;
    private volatile boolean closed;

    SearchDomainDnsResolver(DnsResolver delegate, List<String> searchDomains, int ndots) {
        super(delegate);
        this.searchDomains = searchDomains;
        this.ndots = ndots;
    }

    @Override
    public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
        final SearchDomainQuestionContext searchDomainCtx =
                new SearchDomainQuestionContext(question, searchDomains, ndots);
        final DnsQuestion firstQuestion = searchDomainCtx.nextQuestion();
        assert firstQuestion != null;
        return resolve0(ctx, searchDomainCtx, firstQuestion);
    }

    private CompletableFuture<List<DnsRecord>> resolve0(DnsQuestionContext ctx,
                                                        SearchDomainQuestionContext searchDomainCtx,
                                                        DnsQuestion question) {
        if (closed) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(
                    new IllegalStateException("resolver is closed already"));
        }

        return unwrap().resolve(ctx, question).handle((records, cause) -> {
            if (records != null) {
                return UnmodifiableFuture.completedFuture(records);
            } else {
                final DnsQuestion nextQuestion = searchDomainCtx.nextQuestion();
                if (nextQuestion != null) {
                    // Attempt to query the next search domain
                    return resolve0(ctx, searchDomainCtx, nextQuestion);
                } else {
                    return UnmodifiableFuture.<List<DnsRecord>>exceptionallyCompletedFuture(cause);
                }
            }
        }).thenCompose(Function.identity());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        unwrap().close();
    }

    @VisibleForTesting
    static final class SearchDomainQuestionContext {

        private final DnsQuestion original;
        private final String hostname;
        private final List<String> searchDomains;
        private final boolean shouldStartWithHostname;
        private volatile int numAttemptsSoFar;

        SearchDomainQuestionContext(DnsQuestion original, List<String> searchDomains, int ndots) {
            this.original = original;
            this.searchDomains = searchDomains;
            hostname = original.name();
            shouldStartWithHostname = hasNDots(hostname, ndots);
        }

        private static boolean hasNDots(String hostname, int ndots) {
            for (int idx = hostname.length() - 1, dots = 0; idx >= 0; idx--) {
                if (hostname.charAt(idx) == '.' && ++dots >= ndots) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        DnsQuestion nextQuestion() {
            final DnsQuestion dnsQuestion = nextQuestion0();
            if (dnsQuestion != null) {
                numAttemptsSoFar++;
            }
            return dnsQuestion;
        }

        @Nullable
        private DnsQuestion nextQuestion0() {
            final int numAttemptsSoFar = this.numAttemptsSoFar;
            if (numAttemptsSoFar == 0) {
                if (hostname.endsWith(".") || searchDomains.isEmpty()) {
                    return original;
                }
                if (shouldStartWithHostname) {
                    return newQuestion(hostname);
                } else {
                    final String searchDomain = searchDomains.get(0);
                    return newQuestion(hostname + '.' + searchDomain + '.');
                }
            }

            int nextSearchDomainPos = numAttemptsSoFar;
            if (shouldStartWithHostname) {
                nextSearchDomainPos = numAttemptsSoFar - 1;
            }

            if (nextSearchDomainPos < searchDomains.size()) {
                return newQuestion(hostname + '.' + searchDomains.get(nextSearchDomainPos) + '.');
            }
            if (nextSearchDomainPos == searchDomains.size() && !shouldStartWithHostname) {
                return newQuestion(hostname + '.');
            }
            return null;
        }

        private DnsQuestion newQuestion(String hostname) {
            return DnsQuestionWithoutTrailingDot.of(hostname, original.type());
        }
    }
}
