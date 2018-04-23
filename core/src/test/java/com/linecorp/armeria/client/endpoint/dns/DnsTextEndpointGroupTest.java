/*
 * Copyright 2018    LINE Corporation
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

import static io.netty.handler.codec.dns.DnsRecordType.TXT;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecord;

public class DnsTextEndpointGroupTest {

    @Rule
    public final TestRule globalTimeout = new DisableOnDebug(new Timeout(30, TimeUnit.SECONDS));

    @Test
    public void txt() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", TXT),
                new DefaultDnsResponse(0).addRecord(ANSWER, newTxtRecord("foo.com.", "endpoint=a.foo.com"))
                                         .addRecord(ANSWER, newTxtRecord("foo.com.", "endpoint=b.foo.com"))
                                         .addRecord(ANSWER, newTxtRecord("unrelated.com.", "endpoint=c.com"))
                                         .addRecord(ANSWER, newTooShortTxtRecord("foo.com."))
                                         .addRecord(ANSWER, newTooLongTxtRecord("foo.com."))
                                         .addRecord(ANSWER, newTxtRecord("foo.com.", "unrelated_txt"))
                                         .addRecord(ANSWER, newTxtRecord("foo.com.", "endpoint=group:foo"))
                                         .addRecord(ANSWER, newTxtRecord("foo.com.", "endpoint=b:a:d"))
        ))) {
            try (DnsTextEndpointGroup group = new DnsTextEndpointGroupBuilder("foo.com", txt -> {
                final String txtStr = new String(txt, StandardCharsets.US_ASCII);
                if (txtStr.startsWith("endpoint=")) {
                    return Endpoint.parse(txtStr.substring(9));
                } else {
                    return null;
                }
            }).serverAddresses(server.addr()).build()) {

                assertThat(group.awaitInitialEndpoints()).containsExactly(
                        Endpoint.of("a.foo.com"),
                        Endpoint.of("b.foo.com"));
            }
        }
    }

    private static DnsRecord newTxtRecord(String hostname, String text) {
        final ByteBuf content = Unpooled.buffer();
        content.writeByte(text.length());
        content.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        return new DefaultDnsRawRecord(hostname, TXT, 60, content);
    }

    private static DnsRecord newTooShortTxtRecord(String hostname) {
        return new DefaultDnsRawRecord(hostname, TXT, 60, Unpooled.EMPTY_BUFFER);
    }

    private static DnsRecord newTooLongTxtRecord(String hostname) {
        return new DefaultDnsRawRecord(hostname, TXT, 60, Unpooled.wrappedBuffer(new byte[] {
                1, 0, 0 // Contains one more byte than expected
        }));
    }
}
