/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsRecordType.AAAA;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.TestTcpDnsServer;

import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;

class DnsOverTcpTest {

    @Test
    void shouldFallbackToTcpOnTruncated() throws Exception {
        try (TestTcpDnsServer server = new TestTcpDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))
                                         .addRecord(ANSWER, newAddressRecord("unrelated.com", "1.2.3.4")),
                new DefaultDnsQuestion("foo.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1"))));
             DnsAddressEndpointGroup group =
                     DnsAddressEndpointGroup.builder("foo.com")
                                            .port(8080)
                                            .serverAddresses(server.addr())
                                            .queryTimeoutMillisForEachAttempt(1000)
                                            .dnsCache(NoopDnsCache.INSTANCE)
                                            .build()) {

            assertThat(group.whenReady().get()).containsExactly(
                    Endpoint.of("foo.com", 8080).withIpAddr("1.1.1.1"));
        }
    }
}
