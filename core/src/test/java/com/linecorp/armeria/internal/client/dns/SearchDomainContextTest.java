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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.client.dns.SearchDomainDnsResolver.SearchDomainQuestionContext;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;

class SearchDomainQuestionContextTest {

    @CsvSource({ "foo.com, 1", "bar.foo.com, 1", "bar.foo.com, 2" })
    @ParameterizedTest
    void startsWithHostname(String hostname, int ndots) {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.A);
        final ImmutableList<String> searchDomains = ImmutableList.of("armeria.io", "armeria.com",
                                                                     "armeria.org", "armeria.dev");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, ndots);
        final DnsQuestion firstQuestion = ctx.nextQuestion();
        assertThat(firstQuestion).isEqualTo(original);
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of(hostname + '.' + searchDomain + '.', DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }
        assertThat(ctx.nextQuestion()).isNull();
    }

    @CsvSource({ "foo.com, 2", "bar.foo.com, 3", "bar.foo.com, 4" })
    @ParameterizedTest
    void endsWithHostname(String hostname, int ndots) {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.A);
        final ImmutableList<String> searchDomains = ImmutableList.of("armeria.io", "armeria.com",
                                                                     "armeria.org", "armeria.dev");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, ndots);
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of(hostname + '.' + searchDomain + '.', DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }

        final DnsQuestion lastQuestion = ctx.nextQuestion();
        assertThat(lastQuestion).isEqualTo(DnsQuestionWithoutTrailingDot.of(hostname + '.', DnsRecordType.A));
        assertThat(ctx.nextQuestion()).isNull();
    }

    @Test
    void noSearchDomain() {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of("foo.com", DnsRecordType.A);
        final SearchDomainQuestionContext ctx =
                new SearchDomainQuestionContext(original, ImmutableList.of(), 2);
        assertThat(ctx.nextQuestion()).isEqualTo(original);
        assertThat(ctx.nextQuestion()).isNull();
    }
}
