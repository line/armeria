/*
 * Copyright 2020 LINE Corporation
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
import static io.netty.handler.codec.dns.DnsRecordType.CNAME;
import static io.netty.handler.codec.dns.DnsRecordType.SRV;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.endpoint.dns.DnsNameEncoder;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.ReferenceCountUtil;

public class DnsMetricsTest {

    @Test
    void success() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1"))
                                         .addRecord(ANSWER, newAddressRecord("unrelated.com", "1.2.3.4")),
                new DefaultDnsQuestion("foo.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1"))
        ))) {
            final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.dnsServerAddressStreamProvider(dnsServerList(server));
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                      })
                                      .meterRegistry(meterRegistry)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                final String writeMeterId =
                        "armeria.client.dns.queries.written#count{name=foo.com.,server=" +
                        getHostAddress(server) + '}';
                final String successMeterId =
                        "armeria.client.dns.queries#count{cause=none,name=foo.com.,result=success}";
                final String otherExceptionId =
                        "armeria.client.dns.queries#count{" +
                        "cause=others,name=bar.com.,result=failure}";
                assertThat(MoreMeters.measureAll(meterRegistry))
                        .doesNotContainKeys(writeMeterId, successMeterId);

                client.get("http://foo.com:1/").aggregate();

                await().untilAsserted(() -> {
                    assertThat(MoreMeters.measureAll(meterRegistry))
                            .containsEntry(writeMeterId, 1.0)
                            .containsEntry(successMeterId, 1.0)
                            .doesNotContainKey(otherExceptionId);
                });
            }
        }
    }

    private static String getHostAddress(TestDnsServer server) {
        final String value = server.addr().getAddress().getHostAddress();
        final int percentIdx = value.indexOf('%');
        return percentIdx < 0 ? value : value.substring(0, percentIdx);
    }

    @Test
    void timeout() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1"))
        ), new AlwaysTimeoutHandler())) {
            final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.dnsServerAddressStreamProvider(dnsServerList(server));
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                          builder.queryTimeout(Duration.ofSeconds(5));
                                      })
                                      .meterRegistry(meterRegistry)
                                      .build()) {

                final WebClient client2 = WebClient.builder()
                                                   .factory(factory)
                                                   .build();

                final String writeMeterId_ipv4_addr =
                        "armeria.client.dns.queries.written#count{name=foo.com.,server=127.0.0.1}";
                final String writeMeterId_ipv6_addr =
                        "armeria.client.dns.queries.written#count{name=foo.com.,server=0:0:0:0:0:0:0:1}";
                final String timeoutMeterId =
                        "armeria.client.dns.queries#count{" +
                        "cause=resolver_timeout,name=foo.com.,result=failure}";
                final String otherExceptionId =
                        "armeria.client.dns.queries#count{" +
                        "cause=others,name=bar.com.,result=failure}";
                assertThat(MoreMeters.measureAll(meterRegistry))
                        .doesNotContainKeys(writeMeterId_ipv4_addr, writeMeterId_ipv6_addr, timeoutMeterId);

                final Throwable cause = catchThrowable(
                        () -> client2.execute(RequestHeaders.of(HttpMethod.GET, "http://foo.com"))
                                     .aggregate().join());
                assertThat(cause.getCause()).isInstanceOf(UnprocessedRequestException.class);
                assertThat(Throwables.getRootCause(cause))
                        .isInstanceOfAny(DnsTimeoutException.class, DnsNameResolverTimeoutException.class);

                await().untilAsserted(() -> {
                    assertThat(MoreMeters.measureAll(meterRegistry))
                            .containsAnyOf(entry(writeMeterId_ipv6_addr, 1.0),
                                           entry(writeMeterId_ipv4_addr, 1.0))
                            .doesNotContainKey(otherExceptionId);
                });
            }
        }
    }

    @Test
    void nxDomain() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0, DnsOpCode.QUERY, DnsResponseCode.NXDOMAIN)
        ))) {
            final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();

            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.dnsServerAddressStreamProvider(dnsServerList(server));
                                          builder.searchDomains();
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                      })
                                      .meterRegistry(meterRegistry)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                final String writtenMeterId =
                        "armeria.client.dns.queries.written#count{name=bar.com.,server=" +
                        getHostAddress(server) + '}';
                final String nxDomainMeterId =
                        "armeria.client.dns.queries#count{" +
                        "cause=nx_domain,name=bar.com.,result=failure}";
                final String otherExceptionId =
                        "armeria.client.dns.queries#count{" +
                        "cause=others,name=bar.com.,result=failure}";

                assertThatThrownBy(() -> client.get("http://bar.com").aggregate().join())
                        .hasRootCauseInstanceOf(UnknownHostException.class);

                await().untilAsserted(() -> {
                    assertThat(MoreMeters.measureAll(meterRegistry))
                            .containsEntry(writtenMeterId, 2.0)
                            .containsEntry(nxDomainMeterId, 2.0)
                            .doesNotContainKey(otherExceptionId);
                });
            }
        }
    }

    @Test
    void noAnswer() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0, DnsOpCode.QUERY, DnsResponseCode.NOTZONE)
        ))) {
            final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();

            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.dnsServerAddressStreamProvider(dnsServerList(server));
                                          builder.searchDomains();
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                      })
                                      .meterRegistry(meterRegistry)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                final String writtenMeterId =
                        "armeria.client.dns.queries.written#count{name=bar.com.,server=" +
                        getHostAddress(server) + '}';
                final String noAnswerMeterId =
                        "armeria.client.dns.queries.noanswer#count{code=10,name=bar.com.}";
                final String nxDomainMeterId =
                        "armeria.client.dns.queries#count{" +
                        "cause=nx_domain,name=bar.com.,result=failure}";
                final String otherExceptionId =
                        "armeria.client.dns.queries#count{" +
                        "cause=others,name=bar.com.,result=failure}";
                assertThat(MoreMeters.measureAll(meterRegistry)).doesNotContainKeys(
                        writtenMeterId, noAnswerMeterId, nxDomainMeterId, nxDomainMeterId);

                assertThatThrownBy(() -> client.get("http://bar.com").aggregate().join())
                        .hasRootCauseInstanceOf(UnknownHostException.class);

                assertThat(MoreMeters.measureAll(meterRegistry))
                        .containsEntry(writtenMeterId, 2.0)
                        .containsEntry(noAnswerMeterId, 1.0)
                        .containsEntry(nxDomainMeterId, 1.0)
                        .doesNotContainKey(otherExceptionId);
            }
        }
    }

    @Test
    void cname() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newCnameRecord("bar.com.", "baz.com.")),
                new DefaultDnsQuestion("baz.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "127.0.0.1"))
        ))) {
            final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.dnsServerAddressStreamProvider(dnsServerList(server));
                                          builder.searchDomains();
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                      })
                                      .meterRegistry(meterRegistry)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                final String writtenMeterId =
                        "armeria.client.dns.queries.written#count{name=bar.com.,server=" +
                        getHostAddress(server) + '}';
                final String cnamed =
                        "armeria.client.dns.queries.cnamed#count{cname=baz.com.,name=bar.com.}";
                final String successMeterId =
                        "armeria.client.dns.queries#count{cause=none,name=bar.com.,result=success}";
                final String otherExceptionId =
                        "armeria.client.dns.queries#count{" +
                        "cause=others,name=bar.com.,result=failure}";
                client.get("http://bar.com:1/").aggregate();

                await().untilAsserted(() -> {
                    assertThat(MoreMeters.measureAll(meterRegistry))
                            .containsEntry(writtenMeterId, 2.0)
                            .containsEntry(cnamed, 1.0)
                            .containsEntry(successMeterId, 2.0)
                            .doesNotContainKey(otherExceptionId);
                });
            }
        }
    }

    private static DnsServerAddressStreamProvider dnsServerList(TestDnsServer dnsServer) {
        final InetSocketAddress dnsServerAddr = dnsServer.addr();
        return hostname -> DnsServerAddresses.sequential(ImmutableList.of(dnsServerAddr)).stream();
    }

    private static class AlwaysTimeoutHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                // Just release the msg and return so that the client request is timed out.
                ReferenceCountUtil.safeRelease(msg);
                return;
            }
            super.channelRead(ctx, msg);
        }
    }

    public static DnsRecord newCnameRecord(String name, String actualName) {
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
}
