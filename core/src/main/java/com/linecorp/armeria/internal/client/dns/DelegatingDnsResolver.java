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

import static com.linecorp.armeria.internal.common.NettyFutureUtil.toCompletableFuture;
import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.DnsTimeoutException;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.EventExecutor;

final class DelegatingDnsResolver implements DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(DelegatingDnsResolver.class);
    private static final List<DnsRecord> EMPTY_ADDITIONALS = ImmutableList.of();

    private final DnsNameResolver delegate;
    private final EventExecutor executor;

    DelegatingDnsResolver(DnsNameResolver delegate, EventExecutor executor) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.executor = requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
        requireNonNull(ctx, "ctx");
        requireNonNull(question, "question");
        if (ctx.isCancelled()) {
            return exceptionallyCompletedFuture(new DnsTimeoutException(
                    question + " is timed out after " + ctx.queryTimeoutMillis() + " milliseconds."));
        }

        logger.debug("[{}] Sending a DNS query: {}", question.name(), question);
        return toCompletableFuture(delegate.resolveAll(question, EMPTY_ADDITIONALS, executor.newPromise()));
    }

    @Override
    public void close() {
        delegate.close();
    }
}
