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

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.dns.ByteArrayDnsRecord;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsQuestionWithoutTrailingDot;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.EventExecutor;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS {@code TXT} records. This is useful for
 * environments where service discovery is handled using DNS.
 */
public final class DnsTextEndpointGroup extends DnsEndpointGroup {

    /**
     * Creates a {@link DnsTextEndpointGroup} that schedules queries on a random {@link EventLoop} from
     * {@link CommonPools#workerGroup()}.
     *
     * @param hostname the hostname to query DNS queries for
     * @param mapping the {@link Function} that maps the content of a {@code TXT} record into
     *                an {@link Endpoint}. The {@link Function} is expected to return {@code null}
     *                if the record contains unsupported content.
     */
    public static DnsTextEndpointGroup of(String hostname, Function<byte[], @Nullable Endpoint> mapping) {
        return builder(hostname, mapping).build();
    }

    /**
     * Returns a new {@link DnsTextEndpointGroupBuilder} with
     * the specified hostname and {@link Function} mapping.
     *
     * @param hostname the hostname to query DNS queries for
     * @param mapping the {@link Function} that maps the content of a {@code TXT} record into
     *                an {@link Endpoint}. The {@link Function} is expected to return {@code null}
     *                if the record contains unsupported content.
     */
    public static DnsTextEndpointGroupBuilder builder(String hostname,
                                                      Function<byte[], @Nullable Endpoint> mapping) {
        return new DnsTextEndpointGroupBuilder(hostname, mapping);
    }

    private final Function<byte[], @Nullable Endpoint> mapping;

    DnsTextEndpointGroup(
            EndpointSelectionStrategy selectionStrategy, EventLoop eventLoop, Backoff backoff,
            int minTtl, int maxTtl, String hostname, Function<byte[], @Nullable Endpoint> mapping,
            BiFunction<DnsNameResolverBuilder, EventExecutor, DefaultDnsResolver> resolverFactory) {
        super(selectionStrategy, eventLoop,
              ImmutableList.of(DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.TXT)),
              backoff, minTtl, maxTtl,
              unused -> {}, resolverFactory);
        this.mapping = mapping;
        start();
    }

    @Override
    ImmutableSortedSet<Endpoint> onDnsRecords(List<DnsRecord> records, int ttl) throws Exception {
        final ImmutableSortedSet.Builder<Endpoint> builder = ImmutableSortedSet.naturalOrder();
        for (DnsRecord r : records) {
            if (!(r instanceof ByteArrayDnsRecord) || r.type() != DnsRecordType.TXT) {
                continue;
            }

            final byte[] content = ((ByteArrayDnsRecord) r).content();
            if (content.length == 0) { // Missing length octet
                warnInvalidRecord(DnsRecordType.TXT, content);
                continue;
            }

            final int txtLen = content[0] & 0xFF;
            if (txtLen == 0) { // Empty content
                continue;
            }

            if (content.length != txtLen + 1) { // Mismatching number of octets
                warnInvalidRecord(DnsRecordType.TXT, content);
                continue;
            }

            final byte[] txt = new byte[txtLen];
            System.arraycopy(content, 1, txt, 0, txtLen);

            final Endpoint endpoint;
            try {
                endpoint = mapping.apply(txt);
            } catch (Exception e) {
                warnInvalidRecord(DnsRecordType.TXT, content);
                continue;
            }

            if (endpoint != null) {
                builder.add(endpoint);
            }
        }

        final ImmutableSortedSet<Endpoint> endpoints = builder.build();
        logDnsResolutionResult(endpoints, ttl);
        return endpoints;
    }
}
