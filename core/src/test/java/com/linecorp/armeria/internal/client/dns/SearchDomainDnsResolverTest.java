/*
 *  Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.client.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;

class SearchDomainDnsResolverTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void searchDomainStartingWithDot() {
        final ByteArrayDnsRecord record = new ByteArrayDnsRecord("example.com", DnsRecordType.A,
                                                                 1, new byte[] { 10, 0, 1, 1 });
        final Queue<DnsQuestion> questions = new LinkedBlockingQueue<>();
        final DnsResolver mockResolver = new DnsResolver() {

            @Override
            public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
                questions.add(question);
                if ("example.com.".equals(question.name())) {
                    return UnmodifiableFuture.completedFuture(ImmutableList.of(record));
                } else {
                    return UnmodifiableFuture.exceptionallyCompletedFuture(new AnticipatedException());
                }
            }

            @Override
            public void close() {}
        };

        final List<String> searchDomains = ImmutableList.of(".", "armeria.io", "..invalid.com", ".armeria.dev");
        final DnsQuestionContext context = new DnsQuestionContext(eventLoop.get(), 10000);
        final DnsQuestionWithoutTrailingDot question =
                DnsQuestionWithoutTrailingDot.of("example.com", DnsRecordType.A);
        final SearchDomainDnsResolver resolver = new SearchDomainDnsResolver(mockResolver, searchDomains, 2);
        final CompletableFuture<List<DnsRecord>> result = resolver.resolve(context, question);

        assertThat(result.join()).contains(record);
        assertThat(questions).hasSize(3);
        // Should not send a search domain query with '..invalid.com'
        assertThat(questions).containsExactly(
                DnsQuestionWithoutTrailingDot.of("example.com", "example.com.armeria.io.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("example.com", "example.com.armeria.dev.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("example.com", "example.com.", DnsRecordType.A));
        context.cancel();
    }
}
