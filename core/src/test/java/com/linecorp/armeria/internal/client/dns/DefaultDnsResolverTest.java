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

import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static com.linecorp.armeria.internal.client.dns.DefaultDnsResolver.maybeCompletePreferredRecords;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.NoopDnsCache;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;

class DefaultDnsResolverTest {

    /**
     * This test does not check anything but makes the future returned by
     * {@link DefaultDnsResolver#resolve(List, String)}} is completed even before the DNS responses
     * are received. By doing so, we can make our leak detectors detect any leaks if we forgot to release
     * the DNS responses. See https://github.com/line/armeria/pull/2951 for more information.
     */
    @Test
    void responseReceivedAfterCancellation() throws Exception {
        try (TestDnsServer dnsServer = new TestDnsServer(
                ImmutableMap.of(new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("foo.com.", "1.2.3.4")),
                                new DefaultDnsQuestion("bar.com.", DnsRecordType.A),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("bar.com.", "5.6.7.8"))),
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ctx.executor().schedule(() -> ctx.writeAndFlush(msg, promise), 1, TimeUnit.SECONDS);
                    }
                })) {

            final EventLoop eventLoop = CommonPools.workerGroup().next();
            final DefaultDnsResolver resolver =
                    DefaultDnsResolver.of(
                            new DnsNameResolverBuilder(eventLoop)
                                    .channelType(TransportType.datagramChannelType(eventLoop))
                                    .queryTimeoutMillis(TimeUnit.HOURS.toMillis(1))
                                    .nameServerProvider(
                                            name -> DnsServerAddresses.sequential(dnsServer.addr()).stream())
                                    .build(),
                            DnsCache.ofDefault(), eventLoop, ImmutableList.of(), 1,
                            1, HostsFileEntriesResolver.DEFAULT);

            final DnsQuestionContext ctx = new DnsQuestionContext(eventLoop, 1);
            // resolver.resolveAll() should be executed by the event loop set to DnsNameResolver.
            eventLoop.submit(() -> {
                resolver.resolveAll(ctx,
                                    ImmutableList.of(new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                                                     new DefaultDnsQuestion("bar.com.", DnsRecordType.A)),
                                    "");
            }).get();

            // Cancel the queries immediately.
            ctx.whenCancelled().cancel(false);

            // Wait until the DNS server sends the response.
            Thread.sleep(2000);
            resolver.close();
        }
    }

    @CsvSource({ "IPV4_PREFERRED", "IPV6_PREFERRED" })
    @ParameterizedTest
    void resolveLessPreferredQuestionFirst(ResolvedAddressTypes resolvedAddressType) throws Exception {
        try (TestDnsServer dnsServer = new TestDnsServer(
                ImmutableMap.of(new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("foo.com.", "1.2.3.4")),
                                new DefaultDnsQuestion("foo.com.", DnsRecordType.AAAA),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("foo.com.",
                                                                                     "2001:db8::1"))),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof DatagramDnsQuery) {
                            final DatagramDnsQuery dnsQuery = (DatagramDnsQuery) msg;
                            final DnsRecord dnsRecord = dnsQuery.recordAt(DnsSection.QUESTION, 0);
                            if (dnsRecord.type() == DnsRecordType.AAAA) {
                                // Just release the msg and return so that the client request is timed out.
                                ReferenceCountUtil.safeRelease(msg);
                                return;
                            }
                        }
                        super.channelRead(ctx, msg);
                    }
                })) {

            final EventLoop eventLoop = CommonPools.workerGroup().next();
            final int queryTimeoutMillis = 5000;
            final DefaultDnsResolver resolver =
                    DefaultDnsResolver.of(
                            new DnsNameResolverBuilder(eventLoop)
                                    .channelType(TransportType.datagramChannelType(eventLoop))
                                    .queryTimeoutMillis(TimeUnit.HOURS.toMillis(1))
                                    .nameServerProvider(
                                            name -> DnsServerAddresses.sequential(dnsServer.addr()).stream())
                                    .build(),
                            NoopDnsCache.INSTANCE, eventLoop, ImmutableList.of(), 1,
                            queryTimeoutMillis, HostsFileEntriesResolver.DEFAULT);

            final Stopwatch stopwatch = Stopwatch.createStarted();
            final List<DefaultDnsQuestion> questions;
            if (resolvedAddressType == ResolvedAddressTypes.IPV4_PREFERRED) {
                questions = ImmutableList.of(
                        new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                        new DefaultDnsQuestion("foo.com.", DnsRecordType.AAAA));
            } else {
                questions = ImmutableList.of(
                        new DefaultDnsQuestion("foo.com.", DnsRecordType.AAAA),
                        new DefaultDnsQuestion("foo.com.", DnsRecordType.A));
            }

            // resolver.resolve() should be executed by the event loop set to DnsNameResolver.
            final CompletableFuture<List<DnsRecord>> result = eventLoop.submit(() -> {
                return resolver.resolve(questions, "");
            }).get();

            final List<DnsRecord> records = result.join();

            if (resolvedAddressType == ResolvedAddressTypes.IPV4_PREFERRED) {
                // Should not wait for AAAA to be resolved.
                assertThat(stopwatch.elapsed()).isLessThanOrEqualTo(Duration.ofMillis(queryTimeoutMillis));
            } else {
                // Should wait until AAAA is resolved.
                // The future will be completed by the timeout scheduler.
                assertThat(stopwatch.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(queryTimeoutMillis));
            }
            assertThat(records.size()).isOne();
            final ByteArrayDnsRecord dnsRecord = (ByteArrayDnsRecord) records.get(0);
            assertThat(dnsRecord.type()).isEqualTo(DnsRecordType.A);
            assertThat(NetUtil.bytesToIpAddress(dnsRecord.content())).isEqualTo("1.2.3.4");

            resolver.close();
        }
    }

    @Test
    void shouldWaitForPreferredRecords() {
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        final List<? extends DnsQuestion> questions = ImmutableList.of(
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.AAAA));
        final Object[] results = new Object[questions.size()];

        final List<DnsRecord> fooDnsRecord = ImmutableList.of(newAddressRecord("foo.com.", "1.2.3.4"));
        final List<DnsRecord> barDnsRecord = ImmutableList.of(newAddressRecord("foo.com.", "2001:db8::1"));
        // Should not complete `future` and wait for the first result.
        maybeCompletePreferredRecords(future, questions, results, 1, barDnsRecord, null);
        assertThat(future).isNotCompleted();
        maybeCompletePreferredRecords(future, questions, results, 0, fooDnsRecord, null);
        assertThat(future).isCompletedWithValue(fooDnsRecord);
    }

    @Test
    void shouldWaitForPreferredRecords_ignoreErrorsOnPrecedence() {
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        final List<? extends DnsQuestion> questions = ImmutableList.of(
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.AAAA));
        final Object[] results = new Object[questions.size()];

        final List<DnsRecord> barDnsRecord = ImmutableList.of(newAddressRecord("foo.com.", "2001:db8::1"));
        // Should not complete `future` and wait for the first result.
        maybeCompletePreferredRecords(future, questions, results, 1, barDnsRecord, null);
        assertThat(future).isNotCompleted();
        maybeCompletePreferredRecords(future, questions, results, 0, null, new AnticipatedException());
        assertThat(future).isCompletedWithValue(barDnsRecord);
    }

    @Test
    void resolvePreferredRecordsFirst() {
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        final List<? extends DnsQuestion> questions = ImmutableList.of(
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.AAAA));
        final Object[] results = new Object[questions.size()];

        final List<DnsRecord> fooDnsRecord = ImmutableList.of(newAddressRecord("foo.com.", "1.2.3.4"));
        maybeCompletePreferredRecords(future, questions, results, 0, fooDnsRecord, null);
        // The preferred question is resolved. Don't need to wait for the questions.
        assertThat(future).isCompletedWithValue(fooDnsRecord);
    }

    @Test
    void shouldWaitForPreferredRecords_allQuestionsAreFailed() {
        final CompletableFuture<List<DnsRecord>> future = new CompletableFuture<>();
        final List<? extends DnsQuestion> questions = ImmutableList.of(
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A),
                DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.AAAA));
        final Object[] results = new Object[questions.size()];

        final List<DnsRecord> barDnsRecord = ImmutableList.of(newAddressRecord("foo.com.", "2001:db8::1"));
        // Should not complete `future` and wait for the first result.
        final AnticipatedException barCause = new AnticipatedException();
        maybeCompletePreferredRecords(future, questions, results, 1, barDnsRecord, barCause);
        assertThat(future).isNotCompleted();
        final AnticipatedException fooCause = new AnticipatedException();
        maybeCompletePreferredRecords(future, questions, results, 0, null, fooCause);
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(UnknownHostException.class, cause -> {
                    assertThat(cause.getSuppressed()).contains(fooCause, barCause);
                });
    }
}
