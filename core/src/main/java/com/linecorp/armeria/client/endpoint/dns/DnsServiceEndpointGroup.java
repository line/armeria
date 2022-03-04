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

import java.util.List;
import java.util.function.BiFunction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.client.dns.ByteArrayDnsRecord;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsQuestionWithoutTrailingDot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DefaultDnsRecordDecoder;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.EventExecutor;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS
 * <a href="https://en.wikipedia.org/wiki/SRV_record">SRV records</a>. This is useful for environments
 * where service discovery is handled using DNS, e.g.
 * <a href="https://github.com/kubernetes/dns/blob/master/docs/specification.md">Kubernetes DNS-based service
 * discovery</a>.
 */
public final class DnsServiceEndpointGroup extends DnsEndpointGroup {

    /**
     * Creates a {@link DnsServiceEndpointGroup} that schedules queries on a random {@link EventLoop} from
     * {@link CommonPools#workerGroup()}.
     *
     * @param hostname the hostname to query DNS queries for.
     */
    public static DnsServiceEndpointGroup of(String hostname) {
        return builder(hostname).build();
    }

    /**
     * Returns a new {@link DnsServiceEndpointGroupBuilder} with the specified hostname.
     *
     * @param hostname the hostname to query DNS queries for
     */
    public static DnsServiceEndpointGroupBuilder builder(String hostname) {
        return new DnsServiceEndpointGroupBuilder(hostname);
    }

    DnsServiceEndpointGroup(
            EndpointSelectionStrategy selectionStrategy,
            EventLoop eventLoop, Backoff backoff, int minTtl, int maxTtl, String hostname,
            BiFunction<DnsNameResolverBuilder, EventExecutor, DefaultDnsResolver> resolverFactory) {
        super(selectionStrategy, eventLoop,
              ImmutableList.of(DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.SRV)),
              backoff, minTtl, maxTtl, unused -> {}, resolverFactory);
        start();
    }

    @Override
    ImmutableSortedSet<Endpoint> onDnsRecords(List<DnsRecord> records, int ttl) throws Exception {
        final ImmutableSortedSet.Builder<Endpoint> builder = ImmutableSortedSet.naturalOrder();
        for (DnsRecord r : records) {
            if (!(r instanceof ByteArrayDnsRecord) || r.type() != DnsRecordType.SRV) {
                continue;
            }

            final byte[] content = ((ByteArrayDnsRecord) r).content();
            if (content.length <= 6) { // Too few bytes
                warnInvalidRecord(DnsRecordType.SRV, content);
                continue;
            }

            final ByteBuf contentBuf = Unpooled.wrappedBuffer(content);
            contentBuf.markReaderIndex();
            contentBuf.skipBytes(2);  // priority unused
            final int weight = contentBuf.readUnsignedShort();
            final int port = contentBuf.readUnsignedShort();

            final Endpoint endpoint;
            try {
                final String target = stripTrailingDot(DefaultDnsRecordDecoder.decodeName(contentBuf));
                endpoint = port > 0 ? Endpoint.of(target, port) : Endpoint.of(target);
            } catch (Exception e) {
                warnInvalidRecord(DnsRecordType.SRV, content);
                continue;
            } finally {
                contentBuf.release();
            }

            builder.add(endpoint.withWeight(weight));
        }

        final ImmutableSortedSet<Endpoint> endpoints = builder.build();
        logDnsResolutionResult(endpoints, ttl);
        return endpoints;
    }

    private static String stripTrailingDot(String name) {
        if (name.endsWith(".")) {
            return name.substring(0, name.length() - 1);
        } else {
            return name;
        }
    }
}
