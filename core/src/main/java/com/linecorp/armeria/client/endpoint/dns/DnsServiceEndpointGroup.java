/*
 * Copyright 2017 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.common.CommonPools;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRecordDecoder;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS SRV queries. This is useful for environments
 * where service discovery is handled using DNS - for example, Kubernetes uses SkyDNS for service discovery.
 *
 * <p>DNS response handling is mostly copied from Netty's {@code io.netty.resolver.dns.DnsNameResolverContext}
 * with the following changes.
 *
 * <ul>
 *   <li>Uses SRV record queries</li>
 *   <li>Does not support DNS redirect answers</li>
 *   <li>Does not support querying multiple name servers</li>
 * </ul>
 */
public class DnsServiceEndpointGroup extends DynamicEndpointGroup {

    /**
     * Creates a {@link DnsServiceEndpointGroup} with an unspecified port that schedules queries on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()} every 1 second.
     *
     * @param hostname the hostname to query DNS queries for.
     */
    public static DnsServiceEndpointGroup of(String hostname) {
        return of(hostname, CommonPools.workerGroup().next());
    }

    /**
     * Creates a {@link DnsServiceEndpointGroup} that queries every 1 second.
     *
     * @param hostname the hostname to query DNS queries for.
     * @param eventLoop the {@link EventLoop} to schedule DNS queries on.
     */
    public static DnsServiceEndpointGroup of(String hostname, EventLoop eventLoop) {
        return of(hostname, eventLoop, Duration.ofSeconds(1));
    }

    /**
     * Creates a {@link DnsServiceEndpointGroup}.
     *
     * @param hostname the hostname to query DNS queries for.
     * @param eventLoop the {@link EventLoop} to schedule DNS queries on.
     * @param queryInterval the {@link Duration} to query DNS at.
     */
    public static DnsServiceEndpointGroup of(String hostname, EventLoop eventLoop, Duration queryInterval) {
        return new DnsServiceEndpointGroup(hostname, DnsEndpointGroupUtil.createResolverForEventLoop(eventLoop),
                                           eventLoop, queryInterval);
    }

    private static final Logger logger = LoggerFactory.getLogger(DnsServiceEndpointGroup.class);

    private final String hostname;
    private final DnsNameResolver resolver;
    private final EventLoop eventLoop;
    private final Duration queryInterval;

    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    @VisibleForTesting
    DnsServiceEndpointGroup(String hostname, DnsNameResolver resolver, EventLoop eventLoop,
                            Duration queryInterval) {
        this.hostname = requireNonNull(hostname, "hostname");
        this.resolver = requireNonNull(resolver, "resolver");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.queryInterval = requireNonNull(queryInterval, "queryInterval");
    }

    /**
     * Starts polling for service updates.
     */
    public void start() {
        checkState(scheduledFuture == null, "already started");
        scheduledFuture = eventLoop.scheduleAtFixedRate(this::query, 0, queryInterval.getSeconds(),
                                                        TimeUnit.SECONDS);
    }

    /**
     * Stops polling for service updates.
     */
    @Override
    public void close() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    @VisibleForTesting
    void query() {
        final DnsQuestion question = new DefaultDnsQuestion(hostname, DnsRecordType.SRV);
        final CompletableFuture<List<Endpoint>> promise = new CompletableFuture<>();
        resolver.query(question).addListener(
                (Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) -> {
                    if (future.cause() != null) {
                        logger.warn("Error resolving a domain name: {}", hostname, future.cause());
                        return;
                    }
                    onResponse(question, future.getNow(), promise);
                });
        promise.thenAccept(newEndpoints -> {
            List<Endpoint> endpoints = endpoints();
            if (!endpoints.equals(newEndpoints)) {
                setEndpoints(newEndpoints);
            }
        });
    }

    private void onResponse(
            DnsQuestion question,
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
            CompletableFuture<List<Endpoint>> promise) {
        try {
            final DnsResponse res = envelope.content();
            final DnsResponseCode code = res.code();
            if (code == DnsResponseCode.NOERROR) {
                decodeResponse(question, envelope, promise);
                return;
            }

            if (code != DnsResponseCode.NXDOMAIN) {
                logger.warn(
                        "Name lookup failed on configured name server for hostname: {} - querying other " +
                        "name servers is not supported.", hostname);
            } else {
                logger.warn("No records found for hostname: {}. Is it registered in DNS?", hostname);
            }
            promise.complete(ImmutableList.of());
        } finally {
            ReferenceCountUtil.safeRelease(envelope);
        }
    }

    private void decodeResponse(
            DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
            CompletableFuture<List<Endpoint>> promise) {
        final DnsResponse response = envelope.content();
        final int answerCount = response.count(DnsSection.ANSWER);

        ImmutableList.Builder<Endpoint> resolvedEndpoints = ImmutableList.builder();
        for (int i = 0; i < answerCount; i++) {
            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
            final DnsRecordType type = r.type();
            if (type != DnsRecordType.SRV) {
                continue;
            }

            final String questionName = Ascii.toLowerCase(question.name());
            final String recordName = Ascii.toLowerCase(r.name());

            // Make sure the record is for the questioned domain.
            if (!recordName.equals(questionName)) {
                continue;
            }

            final Endpoint resolved = decodeSrvEndpoint(r);
            if (resolved == null) {
                continue;
            }
            resolvedEndpoints.add(resolved);
            // Note that we do not break from the loop here, so we decode all SRV records.
        }

        promise.complete(resolvedEndpoints.build());
    }

    @Nullable
    private Endpoint decodeSrvEndpoint(DnsRecord record) {
        if (!(record instanceof DnsRawRecord)) {
            return null;
        }
        final ByteBuf recordContent = ((ByteBufHolder) record).content();
        recordContent.readShort();  // priority unused
        int weight = recordContent.readShort();
        int port = recordContent.readUnsignedShort();
        String target = DefaultDnsRecordDecoder.decodeName(recordContent);
        // Last character always a '.'
        target = target.substring(0, target.length() - 1);
        return Endpoint.of(target, port, weight);
    }
}
