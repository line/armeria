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

import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsRecordType.AAAA;
import static io.netty.handler.codec.dns.DnsRecordType.CNAME;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.NoopDnsCache;
import com.linecorp.armeria.client.retry.Backoff;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;

class DnsAddressEndpointGroupTest {

    @Test
    void ipV4Only() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))
                                         .addRecord(ANSWER, newAddressRecord("unrelated.com", "1.2.3.4")),
                new DefaultDnsQuestion("foo.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("foo.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("foo.com", 8080).withIpAddr("1.1.1.1"));
            }
        }
    }

    @Test
    void ipV6Only() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("bar.com.", "1.1.1.1")),
                new DefaultDnsQuestion("bar.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("bar.com.", "::1"))
                                         .addRecord(ANSWER, newAddressRecord("bar.com.", "::1234:5678:90ab"))
                                         .addRecord(ANSWER, newAddressRecord("bar.com.",
                                                                             "2404:6800:4004:806::2013"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("bar.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV6_ONLY)
                                                .build()) {

                assertThat(group.whenReady().get(10, TimeUnit.SECONDS)).containsExactly(
                        Endpoint.of("bar.com", 8080).withIpAddr("2404:6800:4004:806::2013"),
                        Endpoint.of("bar.com", 8080).withIpAddr("::1"),
                        Endpoint.of("bar.com", 8080).withIpAddr("::1234:5678:90ab"));
            }
        }
    }

    @Test
    void ipV4AndIpV6() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("baz.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "1.1.1.1")),
                new DefaultDnsQuestion("baz.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("baz.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("baz.com", 8080).withIpAddr("1.1.1.1"),
                        Endpoint.of("baz.com", 8080).withIpAddr("::1"));
            }
        }
    }

    @Test
    void platformDefault() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("baz.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "1.1.1.1")),
                new DefaultDnsQuestion("baz.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("baz.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).contains(
                        Endpoint.of("baz.com", 8080).withIpAddr("1.1.1.1"));
            }
        }
    }

    @Test
    void cname() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("a.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newBadAddressRecord("a.com.", true))
                                         .addRecord(ANSWER, newCnameRecord("a.com.", "b.com."))
                                         .addRecord(ANSWER, newAddressRecord("b.com.", "1.1.1.1")),
                new DefaultDnsQuestion("a.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newBadAddressRecord("a.com.", false))
                                         .addRecord(ANSWER, newCnameRecord("a.com.", "b.com."))
                                         .addRecord(ANSWER, newAddressRecord("b.com.", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("a.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("a.com", 8080).withIpAddr("1.1.1.1"),
                        Endpoint.of("a.com", 8080).withIpAddr("::1"));
            }
        }
    }

    @Test
    void mixedLoopbackAddresses() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1")),
                new DefaultDnsQuestion("foo.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("foo.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("foo.com", 8080).withIpAddr("127.0.0.1"));
            }
        }
    }

    @Test
    void ipV4MappedOrCompatibleAddresses() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newCompatibleAddressRecord("bar.com.", "1.1.1.1"))
                                         .addRecord(ANSWER, newCompatibleAddressRecord("bar.com.", "1.1.1.2"))
                                         .addRecord(ANSWER, newMappedAddressRecord("bar.com.", "1.1.1.1"))
                                         .addRecord(ANSWER, newMappedAddressRecord("bar.com.", "1.1.1.3"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("bar.com")
                                                .port(8080)
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV6_ONLY)
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("bar.com", 8080).withIpAddr("1.1.1.1"),
                        Endpoint.of("bar.com", 8080).withIpAddr("1.1.1.2"),
                        Endpoint.of("bar.com", 8080).withIpAddr("1.1.1.3"));
            }
        }
    }

    @Test
    void noPort() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("no-port.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("no-port.com", "1.1.1.1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("no-port.com")
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("no-port.com").withIpAddr("1.1.1.1"));
            }
        }
    }

    @Test
    void backoff() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of())) { // Respond nothing.
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("backoff.com")
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                                                .backoff(Backoff.fixed(500))
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                await().untilAsserted(() -> assertThat(group.attemptsSoFar).isGreaterThan(2));
                assertThat(group.endpoints()).isEmpty();

                // Start to respond correctly.
                server.setResponses(ImmutableMap.of(
                        new DefaultDnsQuestion("backoff.com.", A),
                        new DefaultDnsResponse(0)
                                .addRecord(ANSWER, newAddressRecord("backoff.com", "1.1.1.1", 1)),
                        new DefaultDnsQuestion("backoff.com.", AAAA),
                        new DefaultDnsResponse(0)
                                .addRecord(ANSWER, newAddressRecord("backoff.com", "::1", 1))));

                await().untilAsserted(() -> assertThat(group.endpoints()).containsExactly(
                        Endpoint.of("backoff.com").withIpAddr("1.1.1.1"),
                        Endpoint.of("backoff.com").withIpAddr("::1")));
            }
        }
    }

    @Test
    void backoffOnEmptyResponse() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                // Respond with empty records.
                new DefaultDnsQuestion("empty.com.", A), new DefaultDnsResponse(0),
                new DefaultDnsQuestion("empty.com.", AAAA), new DefaultDnsResponse(0)
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("empty.com")
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                                                .backoff(Backoff.fixed(500))
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                await().untilAsserted(() -> assertThat(group.attemptsSoFar).isGreaterThan(2));
                assertThat(group.endpoints()).isEmpty();

                // Start to respond correctly.
                server.setResponses(ImmutableMap.of(
                        new DefaultDnsQuestion("empty.com.", A),
                        new DefaultDnsResponse(0)
                                .addRecord(ANSWER, newAddressRecord("empty.com", "1.1.1.1", 1)),
                        new DefaultDnsQuestion("empty.com.", AAAA),
                        new DefaultDnsResponse(0)
                                .addRecord(ANSWER, newAddressRecord("empty.com", "::1", 1))));

                await().untilAsserted(() -> assertThat(group.endpoints()).containsExactly(
                        Endpoint.of("empty.com").withIpAddr("1.1.1.1"),
                        Endpoint.of("empty.com").withIpAddr("::1")));
            }
        }
    }

    @EnumSource(value = ResolvedAddressTypes.class, names = { "IPV4_PREFERRED", "IPV6_PREFERRED" })
    @ParameterizedTest
    void partialIpV4Response(ResolvedAddressTypes resolvedAddressTypes) throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                // Respond A record only.
                // Respond with NXDOMAIN for AAAA.
                new DefaultDnsQuestion("partial.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("partial.com", "1.1.1.1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("partial.com")
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(resolvedAddressTypes)
                                                .backoff(Backoff.fixed(500))
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("partial.com").withIpAddr("1.1.1.1"));
            }
        }
    }

    @EnumSource(value = ResolvedAddressTypes.class, names = { "IPV4_PREFERRED", "IPV6_PREFERRED" })
    @ParameterizedTest
    void partialIpV6Response(ResolvedAddressTypes resolvedAddressTypes) throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                // Respond AAAA record only.
                // Respond with NXDOMAIN for A.
                new DefaultDnsQuestion("partial.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("partial.com", "::1"))
        ))) {
            try (DnsAddressEndpointGroup group =
                         DnsAddressEndpointGroup.builder("partial.com")
                                                .serverAddresses(server.addr())
                                                .resolvedAddressTypes(resolvedAddressTypes)
                                                .backoff(Backoff.fixed(500))
                                                .dnsCache(NoopDnsCache.INSTANCE)
                                                .build()) {

                assertThat(group.whenReady().get()).containsExactly(
                        Endpoint.of("partial.com").withIpAddr("::1"));
            }
        }
    }

    @Test
    void queryTimeoutForEachAttempt_builder() {
        assertThatThrownBy(() -> {
            DnsAddressEndpointGroup.builder("foo.com")
                                   .queryTimeoutMillisForEachAttempt(-1);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("queryTimeoutMillisForEachAttempt: -1 (expected: > 0)");

        assertThatThrownBy(() -> {
            DnsAddressEndpointGroup.builder("foo.com")
                                   .queryTimeoutForEachAttempt(Duration.ofMillis(-1));
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("queryTimeoutForEachAttempt: PT-0.001S (expected: > 0)");

        assertThatThrownBy(() -> {
            DnsAddressEndpointGroup.builder("foo.com")
                                   .queryTimeoutMillisForEachAttempt(11)
                                   .queryTimeoutMillis(10)
                                   .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("(expected: queryTimeoutMillis >= queryTimeoutMillisForEachAttempt)");

        // Should build successfully.
        DnsAddressEndpointGroup.builder("foo.com")
                               .queryTimeoutMillisForEachAttempt(10)
                               .queryTimeoutMillis(11)
                               .build();
    }

    private static final Logger logger = LoggerFactory.getLogger(DnsAddressEndpointGroupTest.class);

    @CsvSource({ "1", "2", "3" })
    @ParameterizedTest
    void queryTimeoutMillisForEachAttempt_searchDomain(int ndots) throws Exception {
        final List<String> queries = new ArrayList<>();
        final TimeoutHandler timeoutHandler = new TimeoutHandler(dnsRecord -> {
            final String name = dnsRecord.name();
            queries.add(name);
            if ("foo.com.armeria.dev.".equals(name)) {
                return 0;
            } else {
                return Integer.MAX_VALUE;
            }
        });

        try (TestDnsServer server = new TestDnsServer(
                ImmutableMap.of(new DefaultDnsQuestion("foo.com.armeria.dev", A),
                                new DefaultDnsResponse(0)
                                        .addRecord(ANSWER, newAddressRecord("foo.com.armeria.dev", "1.1.1.1"))),
                timeoutHandler);
             DnsAddressEndpointGroup group =
                     DnsAddressEndpointGroup.builder("foo.com")
                                            .port(8080)
                                            .serverAddresses(server.addr())
                                            .queryTimeoutMillisForEachAttempt(1000)
                                            .queryTimeoutMillis(7000)
                                            .ndots(ndots)
                                            // Only "armeria.dev" will be resolved within the timeout.
                                            .searchDomains(ImmutableList.of("armeria.io", "armeria.com",
                                                                            "armeria.dev"))
                                            .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                                            .dnsCache(NoopDnsCache.INSTANCE)
                                            .build()) {
            assertThat(group.whenReady().get()).containsExactly(
                    Endpoint.of("foo.com", 8080).withIpAddr("1.1.1.1"));
            if (ndots == 1) {
                assertThat(queries).containsExactly("foo.com.", "foo.com.armeria.io.",
                                                    "foo.com.armeria.com.", "foo.com.armeria.dev.");
            } else {
                assertThat(queries).containsExactly("foo.com.armeria.io.", "foo.com.armeria.com.",
                                                    "foo.com.armeria.dev.");
            }
        }
    }

    private static DnsRecord newCompatibleAddressRecord(String name, String ipV4Addr) {
        final ByteBuf content = Unpooled.buffer();
        content.writeZero(12);
        content.writeBytes(NetUtil.createByteArrayFromIpAddressString(ipV4Addr));
        return new DefaultDnsRawRecord(name, AAAA, 60, content);
    }

    private static DnsRecord newBadAddressRecord(String name, boolean ipV4) {
        return new DefaultDnsRawRecord(
                name, ipV4 ? A : AAAA, 60, Unpooled.EMPTY_BUFFER);
    }

    private static DnsRecord newMappedAddressRecord(String name, String ipV4Addr) {
        final ByteBuf content = Unpooled.buffer();
        content.writeZero(10);
        content.writeShort(0xFFFF);
        content.writeBytes(NetUtil.createByteArrayFromIpAddressString(ipV4Addr));
        return new DefaultDnsRawRecord(name, AAAA, 60, content);
    }

    private static DnsRecord newCnameRecord(String name, String actualName) {
        final ByteBuf content = Unpooled.buffer();
        DnsNameEncoder.encodeName(actualName, content);
        return new DefaultDnsRawRecord(name, CNAME, 60, content);
    }

    private static class TimeoutHandler extends ChannelInboundHandlerAdapter {
        private final Function<DnsRecord, Integer> delayFunction;

        TimeoutHandler(Function<DnsRecord, Integer> delayFunction) {
            this.delayFunction = delayFunction;
        }


        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                final DatagramDnsQuery dnsQuery = (DatagramDnsQuery) msg;
                final DnsRecord dnsRecord = dnsQuery.recordAt(DnsSection.QUESTION, 0);
                final Integer delayMillis = delayFunction.apply(dnsRecord);
                if (delayMillis == null || delayMillis == 0) {
                    ctx.fireChannelRead(msg);
                    return;
                }
                if (delayMillis.equals(Integer.MAX_VALUE)) {
                    // Just release the msg and return so that the client request is timed out.
                    ReferenceCountUtil.safeRelease(msg);
                    return;
                }
                ctx.executor().schedule(() -> {
                    ctx.fireChannelRead(msg);
                }, delayMillis, TimeUnit.MILLISECONDS);
            }
            super.channelRead(ctx, msg);
        }
    }
}
