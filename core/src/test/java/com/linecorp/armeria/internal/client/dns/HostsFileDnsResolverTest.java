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

import static com.linecorp.armeria.client.endpoint.dns.DnsServiceEndpointGroupTest.newSrvRecord;
import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;

class HostsFileDnsResolverTest {

    @Test
    void respectHostsFile() throws UnknownHostException {
        final DnsRecord fooRecord = new ByteArrayDnsRecord("foo.com", DnsRecordType.A,
                                                           1, new byte[]{ 10, 0, 1, 1 });
        final DnsRecord barRecord = new ByteArrayDnsRecord("bar.com", DnsRecordType.AAAA,
                                                           2, new byte[]{ 10, 0, 1, 2 });
        final DnsRecord quxRecord = ByteArrayDnsRecord.copyOf(newSrvRecord("qux.com", 1, 8080, "a.qux.com"));

        final InetAddress fooHostsFileAddress = InetAddress.getByAddress(new byte[]{ 127, 0, 0, 100 });
        final InetAddress quxHostsFileAddress = InetAddress.getByAddress(new byte[]{ 127, 0, 0, 101 });

        final DnsResolver delegate = new DnsResolver() {

            @Override
            public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
                final DnsRecord record;
                if ("foo.com".equals(question.name())) {
                    record = fooRecord;
                } else if ("bar.com".equals(question.name())) {
                    record = barRecord;
                } else if ("qux.com".equals(question.name())) {
                    record = quxRecord;
                } else {
                    return exceptionallyCompletedFuture(
                            new UnknownHostException("Failed to resolve " + question.name()));
                }
                return CompletableFuture.completedFuture(ImmutableList.of(record));
            }

            @Override
            public void close() {}
        };

        final HostsFileEntriesResolver hostsFileEntriesResolver = (inetHost, resolvedAddressTypes) -> {
            if ("foo.com".equals(inetHost)) {
                return fooHostsFileAddress;
            }
            if ("qux.com".equals(inetHost)) {
                return quxHostsFileAddress;
            }
            return null;
        };
        final HostsFileDnsResolver resolver =
                new HostsFileDnsResolver(delegate, hostsFileEntriesResolver,
                                         ResolvedAddressTypes.IPV4_PREFERRED);

        // A domain name specified in the hosts file.
        List<DnsRecord> records =
                resolver.resolve(null, DnsQuestionWithoutTrailingDot.of("foo.com", DnsRecordType.A)).join();
        assertThat(records).hasSize(1);
        ByteArrayDnsRecord record = (ByteArrayDnsRecord) records.get(0);
        assertThat(record.name()).isEqualTo("foo.com");
        assertThat(record.type()).isEqualTo(DnsRecordType.A);
        assertThat(record.content()).isEqualTo(fooHostsFileAddress.getAddress());

        // A domain name unspecified in the hosts file that should be resolved.
        records = resolver.resolve(null, DnsQuestionWithoutTrailingDot.of("bar.com", DnsRecordType.A)).join();
        assertThat(records).hasSize(1);
        record = (ByteArrayDnsRecord) records.get(0);
        assertThat(record).isEqualTo(barRecord);

        // Only A and AAAA types should be resolved by HostsFileEntriesResolver.
        records = resolver.resolve(null, DnsQuestionWithoutTrailingDot.of("qux.com", DnsRecordType.SRV)).join();
        assertThat(records).hasSize(1);
        record = (ByteArrayDnsRecord) records.get(0);
        assertThat(record).isEqualTo(quxRecord);
    }
}
