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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.client.dns.SearchDomainDnsResolver.SearchDomainQuestionContext;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;

class SearchDomainTest {

    @CsvSource({ "foo.com, 1", "bar.foo.com, 1", "bar.foo.com, 2" })
    @ParameterizedTest
    void startsWithHostname(String hostname, int ndots) {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.A);
        // Since `SearchDomainDnsResolver` normalizes search domains while being initialized,
        // `SearchDomainQuestionContext` should use a normalized search domain that
        // ends with a dot for testing .
        final List<String> searchDomains = ImmutableList.of("armeria.io.", "armeria.com.",
                                                            "armeria.org.", "armeria.dev.");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, ndots);
        final DnsQuestion firstQuestion = ctx.nextQuestion();
        assertThat(firstQuestion.name()).isEqualTo(hostname + '.');
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of(hostname, hostname + '.' + searchDomain,
                                                     DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }
        assertThat(ctx.nextQuestion()).isNull();
    }

    @CsvSource({ "foo.com, 2", "bar.foo.com, 3", "bar.foo.com, 4" })
    @ParameterizedTest
    void endsWithHostname(String hostname, int ndots) {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.A);
        final List<String> searchDomains = ImmutableList.of("armeria.io.", "armeria.com.",
                                                            "armeria.org.", "armeria.dev.");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, ndots);
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of(hostname, hostname + '.' + searchDomain,
                                                     DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }

        final DnsQuestion lastQuestion = ctx.nextQuestion();
        assertThat(lastQuestion.name()).isEqualTo(hostname + '.');
        assertThat(ctx.nextQuestion()).isNull();
    }

    @Test
    void trailingDot() {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A);
        final List<String> searchDomains = ImmutableList.of("armeria.io.", "armeria.com.",
                                                            "armeria.org.", "armeria.dev.");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, 3);
        final DnsQuestion firstQuestion = ctx.nextQuestion();
        assertThat(firstQuestion.name()).isEqualTo("foo.com.");
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of("foo.com.", "foo.com." + searchDomain,
                                                     DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }
        assertThat(ctx.nextQuestion()).isNull();
    }

    @Test
    void nonTrailingDot_shouldStartWithHostnameByNdots() {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of("bar.foo.com", DnsRecordType.A);
        final List<String> searchDomains = ImmutableList.of("armeria.io.", "armeria.com.",
                                                            "armeria.org.", "armeria.dev.");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, 2);
        final DnsQuestion firstQuestion = ctx.nextQuestion();
        assertThat(firstQuestion.name()).isEqualTo("bar.foo.com.");
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of("bar.foo.com", "bar.foo.com." + searchDomain,
                                                     DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }
        assertThat(ctx.nextQuestion()).isNull();
    }

    @Test
    void nonTrailingDot_shouldNotStartWithHostnameByNdots() {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of("bar.foo.com", DnsRecordType.A);
        final List<String> searchDomains = ImmutableList.of("armeria.io.", "armeria.com.",
                                                            "armeria.org.", "armeria.dev.");
        final SearchDomainQuestionContext ctx = new SearchDomainQuestionContext(original, searchDomains, 3);
        for (String searchDomain : searchDomains) {
            final DnsQuestion expected =
                    DnsQuestionWithoutTrailingDot.of("bar.foo.com", "bar.foo.com." + searchDomain,
                                                     DnsRecordType.A);
            assertThat(ctx.nextQuestion()).isEqualTo(expected);
        }
        final DnsQuestion firstQuestion = ctx.nextQuestion();
        assertThat(firstQuestion.name()).isEqualTo("bar.foo.com.");
        assertThat(ctx.nextQuestion()).isNull();
    }

    @Test
    void noSearchDomain() {
        final DnsQuestion original = DnsQuestionWithoutTrailingDot.of("foo.com", DnsRecordType.A);
        final SearchDomainQuestionContext ctx =
                new SearchDomainQuestionContext(original, ImmutableList.of(), 2);
        assertThat(ctx.nextQuestion()).isEqualTo(
                DnsQuestionWithoutTrailingDot.of("foo.com", "foo.com.", DnsRecordType.A));
        assertThat(ctx.nextQuestion()).isNull();
    }
}
