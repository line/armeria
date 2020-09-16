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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsRecordType.AAAA;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;

public class DnsMetricsTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @Test
    void dns_metric_test_for_successful_query_writes() throws ExecutionException, InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))
                        .addRecord(ANSWER, newAddressRecord("unrelated.com", "1.2.3.4")),
                new DefaultDnsQuestion("foo.com.", AAAA),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1"))
        ))) {
            final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                    hostname -> DnsServerAddresses.sequential(
                            Stream.of(server).map(TestDnsServer::addr).collect(toImmutableList())).stream();
            final MeterRegistry pm1 = PrometheusMeterRegistries.newRegistry();
            final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                    .dnsServerAddressStreamProvider(dnsServerAddressStreamProvider)
                    .meterRegistry(pm1)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    .traceEnabled(false);

            final EventLoop eventLoop = eventLoopExtension.get();
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                try (ClientFactory factory = ClientFactory.builder()
                        .addressResolverGroupFactory(builder::build)
                        .meterRegistry(pm1)
                        .build()) {
                    final WebClient client2 = WebClient.builder()
                            .factory(factory)
                            .build();

                    client2.execute(RequestHeaders.of(HttpMethod.GET, "http://foo.com")).aggregate().get();

                    final PrometheusMeterRegistry registry = (PrometheusMeterRegistry) pm1;
                    final Iterator var4 = Collections.list(registry.getPrometheusRegistry()
                            .metricFamilySamples()).iterator();
                    while (var4.hasNext()) {
                        System.out.println(var4.next());
                    }
                    final double count = registry.getPrometheusRegistry()
                            .getSampleValue("armeria_client_dns_queries_total",
                                    new String[] {"cause","name","result"},
                                    new String[] {"none","foo.com.", "success"});
                    assertThat(count > 1.0).isTrue();
                }
            }
        }
    }

    @Test
    void dns_metric_test_for_query_failures() throws ExecutionException, InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(), new AlwaysTimeoutHandler())) {
            final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                    hostname -> DnsServerAddresses.sequential(
                            Stream.of(server).map(TestDnsServer::addr).collect(toImmutableList())).stream();
            final MeterRegistry pm1 = PrometheusMeterRegistries.newRegistry();
            final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                    .dnsServerAddressStreamProvider(dnsServerAddressStreamProvider)
                    .meterRegistry(pm1)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    .traceEnabled(false);

            final EventLoop eventLoop = eventLoopExtension.get();
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                try (ClientFactory factory = ClientFactory.builder()
                        .addressResolverGroupFactory(builder::build)
                        .meterRegistry(pm1)
                        .build()) {
                    final WebClient client2 = WebClient.builder()
                            .factory(factory)
                            .build();

                    assertThatThrownBy(() -> client2.execute(RequestHeaders.of(HttpMethod.GET, "http://foo.com"))
                            .aggregate().join())
                            .hasCauseInstanceOf(UnprocessedRequestException.class)
                            .hasRootCauseExactlyInstanceOf(DnsTimeoutException.class);

                    final PrometheusMeterRegistry registry = (PrometheusMeterRegistry) pm1;
                    final Iterator var4 = Collections.list(registry.getPrometheusRegistry()
                            .metricFamilySamples()).iterator();
                    while (var4.hasNext()) {
                        System.out.println(var4.next());
                    }
                    final double count1 = registry.getPrometheusRegistry()
                            .getSampleValue("armeria_client_dns_queries_written_total",
                                    new String[] {"name","server"},
                                    new String[] {"foo.com.", "0:0:0:0:0:0:0:1"});
                    assertThat(count1 > 1.0).isTrue();
                }
            }
        }
    }

    @Test
    void dns_test_no_answer() throws ExecutionException, InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0, DnsOpCode.QUERY, DnsResponseCode.NOTZONE)
        ))) {
            final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                    hostname -> DnsServerAddresses.sequential(
                            Stream.of(server).map(TestDnsServer::addr).collect(toImmutableList())).stream();
            final MeterRegistry pm1 = PrometheusMeterRegistries.newRegistry();
            final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                    .dnsServerAddressStreamProvider(dnsServerAddressStreamProvider)
                    .meterRegistry(pm1)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    .traceEnabled(false);

            final EventLoop eventLoop = eventLoopExtension.get();
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("bar.com", 36462));
                try (ClientFactory factory = ClientFactory.builder()
                        .addressResolverGroupFactory(builder::build)
                        .meterRegistry(pm1)
                        .build()) {
                    final WebClient client2 = WebClient.builder()
                            .factory(factory)
                            .build();

                    try {
                        client2.execute(RequestHeaders.of(HttpMethod.GET, "http://bar.com"))
                                .aggregate().get();
                    } catch (Exception ex) {
                        final PrometheusMeterRegistry registry =
                                (PrometheusMeterRegistry) pm1;
                        final Iterator var4 = Collections.list(registry.getPrometheusRegistry()
                                .metricFamilySamples()).iterator();
                        while (var4.hasNext()) {
                            System.out.println(var4.next());
                        }

                        final double count = registry.getPrometheusRegistry()
                                .getSampleValue("armeria_client_dns_queries_noanswer_total",
                                        new String[] {"code","name"},
                                        new String[] {"10","bar.com."});
                        assertThat(count > 1.0).isTrue();

                        final double count2 = registry.getPrometheusRegistry()
                                .getSampleValue("armeria_client_dns_queries_total",
                                        new String[] {"cause","name", "result"},
                                        new String[] {"NAME_SERVERS_EXHAUSTED_EXCEPTION",
                                                      "bar.com.", "failure"});
                        assertThat(count2 > 1.0).isTrue();

                        final double count3 = registry.getPrometheusRegistry()
                                .getSampleValue("armeria_client_dns_queries_total",
                                        new String[] {"cause","name","result"},
                                        new String[] {"NX_DOMAIN_QUERY_FAILED_EXCEPTION",
                                                      "bar.com.", "failure"});
                        assertThat(count3 > 1.0).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void test_with_real_dns_query() throws ExecutionException, InterruptedException {
        final MeterRegistry pm1 = PrometheusMeterRegistries.newRegistry();
        try (ClientFactory factory = ClientFactory.builder()
                .meterRegistry(pm1)
                .build()) {
            final WebClient client2 = WebClient.builder()
                    .factory(factory)
                    .build();

            client2.execute(RequestHeaders.of(HttpMethod.GET, "http://google.com")).aggregate().get();
            final PrometheusMeterRegistry registry = (PrometheusMeterRegistry) pm1;
            final Iterator var4 = Collections.list(registry.getPrometheusRegistry()
                    .metricFamilySamples()).iterator();
            while (var4.hasNext()) {
                System.out.println(var4.next());
            }
            final double count = registry.getPrometheusRegistry()
                    .getSampleValue("armeria_client_dns_queries_total",
                            new String[] {"cause","name","result"},
                            new String[] {"none",
                                    "google.com.", "success"});
            assertThat(count > 1.0).isTrue();
        }
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
}
