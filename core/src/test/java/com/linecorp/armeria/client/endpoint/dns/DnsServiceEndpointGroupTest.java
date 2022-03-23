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

import static io.netty.handler.codec.dns.DnsRecordType.CNAME;
import static io.netty.handler.codec.dns.DnsRecordType.SRV;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecord;

public class DnsServiceEndpointGroupTest {

    @Test
    void srv() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", SRV),
                new DefaultDnsResponse(0).addRecord(ANSWER, newSrvRecord("foo.com.", 1, 2, "a.foo.com."))
                                         .addRecord(ANSWER, newSrvRecord("foo.com.", 3, 4, "b.foo.com."))
                                         .addRecord(ANSWER, newSrvRecord("unrelated.com.", 0, 0, "asdf.com."))
                                         .addRecord(ANSWER, newTooShortSrvRecord("foo.com."))
                                         .addRecord(ANSWER, newBadNameSrvRecord("foo.com."))
        ))) {
            try (DnsServiceEndpointGroup group =
                         DnsServiceEndpointGroup.builder("foo.com")
                                                .serverAddresses(server.addr())
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("a.foo.com", 2).withWeight(1),
                        Endpoint.of("b.foo.com", 4).withWeight(3));
            }
        }
    }

    @Test
    void cname() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", SRV),
                new DefaultDnsResponse(0).addRecord(ANSWER, newCnameRecord("bar.com.", "baz.com."))
                                         .addRecord(ANSWER, newSrvRecord("baz.com.", 5, 6, "c.baz.com."))
        ))) {
            try (DnsServiceEndpointGroup group =
                         DnsServiceEndpointGroup.builder("bar.com")
                                                .serverAddresses(server.addr())
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("c.baz.com", 6).withWeight(5));
            }
        }
    }

    @Test
    void noPort() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("no-port.com.", SRV),
                new DefaultDnsResponse(0).addRecord(ANSWER, newSrvRecord("no-port.com.", 7, 0, "d.no-port.com"))
        ))) {
            try (DnsServiceEndpointGroup group =
                         DnsServiceEndpointGroup.builder("no-port.com")
                                                .serverAddresses(server.addr()).build()) {
                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("d.no-port.com"));
            }
        }
    }

    @Test
    void allowEmptyEndpoint() {
        try (DnsServiceEndpointGroup group = DnsServiceEndpointGroup.builder("foo.com")
                                                                    .allowEmptyEndpoints(false)
                                                                    .build()) {
            assertThat(group.allowsEmptyEndpoints()).isFalse();
        }

        try (DnsServiceEndpointGroup group = DnsServiceEndpointGroup.builder("foo.com")
                                                                    .allowEmptyEndpoints(true)
                                                                    .build()) {
            assertThat(group.allowsEmptyEndpoints()).isTrue();
        }
    }

    private static DnsRecord newCnameRecord(String name, String actualName) {
        final ByteBuf content = Unpooled.buffer();
        DnsNameEncoder.encodeName(actualName, content);
        return new DefaultDnsRawRecord(name, CNAME, 60, content);
    }

    public static DnsRecord newSrvRecord(String hostname, int weight, int port, String target) {
        final ByteBuf content = Unpooled.buffer();
        content.writeShort(1); // priority unused
        content.writeShort(weight);
        content.writeShort(port);
        DnsNameEncoder.encodeName(target, content);
        return new DefaultDnsRawRecord(hostname, SRV, 60, content);
    }

    private static DnsRecord newTooShortSrvRecord(String hostname) {
        return new DefaultDnsRawRecord(hostname, SRV, 60, Unpooled.wrappedBuffer(new byte[4]));
    }

    private static DnsRecord newBadNameSrvRecord(String hostname) {
        return new DefaultDnsRawRecord(hostname, SRV, 60, Unpooled.wrappedBuffer(new byte[]{
                0, 0, 0, 0, 0, 0, 127, 127, 127
        }));
    }
}
