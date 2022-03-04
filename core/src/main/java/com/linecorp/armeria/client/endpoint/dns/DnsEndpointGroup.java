/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.dns;

import static com.google.common.base.Preconditions.checkState;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsUtil;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS queries. This is useful for environments
 * where service discovery is handled using DNS, e.g. Kubernetes uses SkyDNS for service discovery.
 */
abstract class DnsEndpointGroup extends DynamicEndpointGroup {

    private final EventLoop eventLoop;
    private final Backoff backoff;
    private final List<DnsQuestion> questions;
    private final DefaultDnsResolver resolver;
    private final Logger logger;
    private final String logPrefix;
    private final int minTtl;
    private final int maxTtl;

    private boolean started;
    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;
    @VisibleForTesting
    int attemptsSoFar;

    DnsEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                     EventLoop eventLoop, List<DnsQuestion> questions, Backoff backoff, int minTtl, int maxTtl,
                     Consumer<DnsNameResolverBuilder> resolverConfigurator,
                     BiFunction<DnsNameResolverBuilder, EventExecutor, DefaultDnsResolver> resolverFactory) {

        super(selectionStrategy);

        this.eventLoop = eventLoop;
        this.backoff = backoff;
        this.questions = questions;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        logger = LoggerFactory.getLogger(getClass());
        logPrefix = this.questions.stream()
                                  .map(DnsQuestion::name)
                                  .distinct()
                                  .collect(Collectors.joining(", "));

        final DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder(eventLoop);
        resolverConfigurator.accept(resolverBuilder);
        resolver = resolverFactory.apply(resolverBuilder, eventLoop);
    }

    final Logger logger() {
        return logger;
    }

    final String logPrefix() {
        return logPrefix;
    }

    /**
     * Invoke this method at the end of the subclass constructor to initiate the queries.
     */
    final void start() {
        checkState(!started);
        started = true;
        eventLoop.execute(() -> sendQueries(questions));
    }

    private void sendQueries(List<DnsQuestion> questions) {
        if (isClosing()) {
            return;
        }

        final CompletableFuture<List<DnsRecord>> future = resolver.resolve(questions, logPrefix);
        attemptsSoFar++;
        future.handle(this::onDnsRecords);
    }

    private Void onDnsRecords(@Nullable List<DnsRecord> records, @Nullable Throwable cause) {
        if (isClosing()) {
            return null;
        }

        if (cause != null) {
            // Failed. Try again with the delay given by Backoff.
            final long delayMillis = backoff.nextDelayMillis(attemptsSoFar);
            logger.warn("{} DNS query failed; retrying in {} ms (attempts so far: {}):",
                        logPrefix, delayMillis, attemptsSoFar, cause);
            scheduledFuture = eventLoop.schedule(() -> sendQueries(questions),
                                                 delayMillis, TimeUnit.MILLISECONDS);
            return null;
        }

        // Reset the counter so that Backoff is reset.
        attemptsSoFar = 0;

        final long serverTtl = records.stream().mapToLong(DnsRecord::timeToLive).min().orElse(minTtl);
        final int effectiveTtl = (int) Math.max(Math.min(serverTtl, maxTtl), minTtl);

        try {
            setEndpoints(onDnsRecords(records, effectiveTtl));
        } catch (Throwable t) {
            logger.warn("{} Failed to process the DNS query result: {}", logPrefix, records, t);
        } finally {
            scheduledFuture = eventLoop.schedule(() -> sendQueries(questions), effectiveTtl, TimeUnit.SECONDS);
        }
        return null;
    }

    /**
     * Invoked when DNS records were retrieved from a DNS server. Implement this method to transform
     * {@link DnsRecord}s into {@link Endpoint}s.
     */
    abstract ImmutableSortedSet<Endpoint> onDnsRecords(List<DnsRecord> records, int ttl) throws Exception;

    /**
     * Stops polling DNS servers for service updates.
     */
    @Override
    protected final void doCloseAsync(CompletableFuture<?> future) {
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        future.complete(null);
    }

    /**
     * Logs a warning message about an invalid record.
     */
    final void warnInvalidRecord(DnsRecordType type, byte[] content) {
        DnsUtil.warnInvalidRecord(logger(), logPrefix, type, content);
    }

    final void logDnsResolutionResult(Collection<Endpoint> endpoints, int ttl) {
        if (endpoints.isEmpty()) {
            logger().warn("{} Resolved to empty endpoints (TTL: {})", logPrefix, ttl);
        } else {
            if (logger().isDebugEnabled()) {
                logger().debug("{} Resolved: {} (TTL: {})",
                               logPrefix,
                               endpoints.stream().map(Object::toString).collect(Collectors.joining(", ")),
                               ttl);
            }
        }
    }
}
