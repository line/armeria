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

import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

public class DefaultDnsNameResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDnsNameResolver.class);

    private final DnsNameResolver delegate;
    private final EventLoop eventLoop;

    public DefaultDnsNameResolver(DnsNameResolver delegate, EventLoop eventLoop) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
    }

    public EventLoop executor() {
        return eventLoop;
    }

    public Future<List<DnsRecord>> sendQueries(List<DnsQuestion> questions, String logPrefix) {
        requireNonNull(questions, "questions");
        requireNonNull(logPrefix, "logPrefix");
        final int numQuestions = questions.size();
        if (numQuestions == 1) {
            // Simple case of single query
            final DnsQuestion question = questions.get(0);
            logger.debug("[{}] Sending a DNS query: {}", logPrefix, question);
            return delegate.resolveAll(question);
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

                if (--remaining == 0) {
                    if (!records.isEmpty()) {
                        aggregatedPromise.setSuccess(records);
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
                        aggregatedPromise.setFailure(aggregatedCause);
                    }
                }
            }
        };

        questions.forEach(q -> delegate.resolveAll(q).addListener(listener));
        return aggregatedPromise;
    }

    public void close() {
        delegate.close();
    }
}
