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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;

class CachingDnsResolverTest {

    @Test
    void refreshingRequest() throws Exception {
        final DnsRecord fooRecord = new ByteArrayDnsRecord("foo.com", DnsRecordType.A,
                                                           10, new byte[] { 10, 0, 1, 1 });

        final AtomicBoolean cacheMiss = new AtomicBoolean();
        final DnsResolver delegate = new DnsResolver() {
            @Override
            public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
                cacheMiss.set(true);
                final DnsRecord record;
                if ("foo.com".equals(question.name())) {
                    record = fooRecord;
                } else {
                    return UnmodifiableFuture.exceptionallyCompletedFuture(
                            new UnknownHostException("Failed to resolve " + question.name()));
                }
                return UnmodifiableFuture.completedFuture(ImmutableList.of(record));
            }

            @Override
            public void close() {}
        };

        final CachingDnsResolver dnsResolver = new CachingDnsResolver(delegate, DnsCache.builder().build());
        final DnsQuestionWithoutTrailingDot question =
                DnsQuestionWithoutTrailingDot.of("foo.com", DnsRecordType.A);
        DnsQuestionContext ctx = new DnsQuestionContext(CommonPools.workerGroup().next(), 0, true);
        List<DnsRecord> records = dnsResolver.resolve(ctx, question).join();
        assertThat(records).containsExactly(fooRecord);
        assertThat(cacheMiss).isTrue();
        cacheMiss.set(false);

        // The cache created more than 2 seconds ago should be refreshed.
        ctx = new DnsQuestionContext(CommonPools.workerGroup().next(), 0, true);
        records = dnsResolver.resolve(ctx, question).join();
        assertThat(records).containsExactly(fooRecord);
        // Make sure that the cached value is ignored.
        assertThat(cacheMiss).isTrue();
        cacheMiss.set(false);
    }
}
