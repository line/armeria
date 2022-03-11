/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.client.DnsTimeoutUtil.assertDnsTimeoutException;
import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsRecordType.AAAA;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.RefreshingAddressResolver.CacheEntry;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;

class RefreshingAddressResolverTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    private CompletableFuture<CacheEntry> noopCacheLoader(String key) {
        return null;
    }

    @Test
    void resolve() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1")),
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("bar.com.", "1.2.3.4"))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            try (RefreshingAddressResolverGroup group = builder(server).build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                InetSocketAddress addr = foo.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.1.1.1");
                assertThat(addr.getPort()).isEqualTo(36462);

                final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.estimatedSize()).isOne();

                final Future<InetSocketAddress> bar = resolver.resolve(
                        InetSocketAddress.createUnresolved("bar.com", 36462));
                await().untilAsserted(() -> assertThat(bar.isSuccess()).isTrue());
                addr = bar.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.2.3.4");
                assertThat(addr.getPort()).isEqualTo(36462);
                assertThat(cache.estimatedSize()).isEqualTo(2);

                final Future<InetSocketAddress> foo1 = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 80));
                addr = foo1.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.1.1.1");
                assertThat(addr.getPort()).isEqualTo(80);
                assertThat(cache.estimatedSize()).isEqualTo(2);

                final List<InetAddress> addresses =
                        cache.asMap()
                             .values()
                             .stream()
                             .map(future -> future.join().address())
                             .collect(toImmutableList());
                assertThat(addresses).containsExactlyInAnyOrder(
                        InetAddress.getByAddress("foo.com", new byte[]{ 1, 1, 1, 1 }),
                        InetAddress.getByAddress("bar.com", new byte[]{ 1, 2, 3, 4 }));
            }
        }
    }

    @Test
    void nonRemovalWhenNoCacheHit() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1", 1))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                assertThat(foo.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");

                Thread.sleep(1100); // wait until refresh cache entry (ttl + a)

                final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.estimatedSize()).isOne();
            }
        }
    }

    @Test
    void refreshing() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("baz.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "1.1.1.1", 1))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            try (RefreshingAddressResolverGroup group = builder(server).build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final long start = System.nanoTime();

                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("baz.com", 36462));
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                assertThat(foo.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");

                final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.estimatedSize()).isOne();
                assertThat(cache.get("baz.com", this::noopCacheLoader).join().address()).isEqualTo(
                        InetAddress.getByAddress("baz.com", new byte[]{ 1, 1, 1, 1 }));

                // Resolve one more to increase cache hits.
                resolver.resolve(InetSocketAddress.createUnresolved("baz.com", 36462));

                server.setResponses(ImmutableMap.of(
                        new DefaultDnsQuestion("baz.com.", A),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "2.2.2.2"))));

                await().until(() -> {
                    final CompletableFuture<CacheEntry> future = cache.get("baz.com",
                                                                           this::noopCacheLoader);
                    return future != null && future.join().address().equals(
                            InetAddress.getByAddress("baz.com", new byte[]{ 2, 2, 2, 2 }));
                });

                assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(
                        (long) (TimeUnit.SECONDS.toNanos(1) * 0.9)); // ttl 2 seconds * buffer (90%)
            }
        }
    }

    @Test
    void removedWhenExceedingBackoffMaxAttempts() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1", 1))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server);
            builder.refreshBackoff(Backoff.ofDefault().withMaxAttempts(1));
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final long start = System.nanoTime();

                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                assertThat(foo.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");

                server.setResponses(ImmutableMap.of());

                // Schedule resolve() every 500 millis to keep cache hits greater than 0.
                for (int i = 1; i <= 4; i++) {
                    eventLoop.schedule(
                            () -> resolver.resolve(InetSocketAddress.createUnresolved("foo.com", 36462)),
                            500 * i, TimeUnit.MILLISECONDS);
                }

                final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
                await().until(() -> cache.estimatedSize() == 0);

                assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(
                        (long) (TimeUnit.SECONDS.toNanos(1) * 0.9)); // buffer (90%)

                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);
                assertThat(future.cause()).isInstanceOf(UnknownHostException.class);
            }
        }
    }

    @Test
    void cacheClearWhenClosed() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final RefreshingAddressResolverGroup group = builder(server).build(eventLoop);
            final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
            final Future<InetSocketAddress> foo = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
            assertThat(foo.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");
            final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
            assertThat(cache.estimatedSize()).isEqualTo(1);
            final CacheEntry cacheEntry = cache.get("foo.com", this::noopCacheLoader).join();
            group.close();
            await().until(() -> {
                final ScheduledFuture<?> future = cacheEntry.refreshFuture;
                return future != null && future.isCancelled();
            });
            assertThat(cache.estimatedSize()).isZero();
        }
    }

    @Test
    void negativeTtl() {
        // TimeoutHandler times out only the first query.
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler())) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder =
                    builder(false, server).negativeTtl(60)
                                          .queryTimeoutMillis(1000);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);

                final Throwable cause = future.cause();
                assertDnsTimeoutException(cause);

                // Because it's timed out, the result is not cached.
                final Cache<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.estimatedSize()).isZero();

                final Future<InetSocketAddress> future2 = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future2::isDone);
                assertThat(future2.cause()).isInstanceOf(UnknownHostException.class)
                                           .hasNoCause();
                // Because it is NXDOMAIN, the result is cached.
                assertThat(cache.estimatedSize()).isOne();
            }
        }
    }

    @Test
    void timeout() {
        try (TestDnsServer server1 = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler());
             TestDnsServer server2 = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler());
             TestDnsServer server3 = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler());
             TestDnsServer server4 = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler());
             TestDnsServer server5 = new TestDnsServer(ImmutableMap.of(
                     new DefaultDnsQuestion("foo.com.", A),
                     new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))) {

            final DnsResolverGroupBuilder builder = builder(false, server1, server2, server3, server4, server5)
                    .negativeTtl(60)
                    .queryTimeoutMillis(1000);

            try (ClientFactory factory = ClientFactory.builder()
                                                      .addressResolverGroupFactory(builder::build)
                                                      .build()) {
                final WebClient client = WebClient.builder("http://foo.com").factory(factory).build();
                final Throwable cause = catchThrowable(() -> client.get("/").aggregate().join());
                assertThat(cause.getCause()).isInstanceOf(UnprocessedRequestException.class);
                assertDnsTimeoutException(cause);
            }
        }
    }

    @Test
    void returnDnsQuestionsWhenAllQueryTimeout() throws Exception {
        try (TestDnsServer server1 = new TestDnsServer(ImmutableMap.of(), new AlwaysTimeoutHandler());
             TestDnsServer server2 = new TestDnsServer(ImmutableMap.of(), new AlwaysTimeoutHandler())) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server1, server2)
                    .queryTimeoutMillis(1000)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);
                assertDnsTimeoutException(future.cause());
            }
        }
    }

    @Test
    void returnPartialDnsQuestions() throws Exception {
        // Returns IPv6 correctly and make IPv4 timeout.
        try (TestDnsServer server = new TestDnsServer(
                ImmutableMap.of(
                        new DefaultDnsQuestion("foo.com.", AAAA),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1", 1))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server)
                    .queryTimeoutMillis(1000)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);
                assertThat(future.getNow().getAddress().getHostAddress()).isEqualTo("0:0:0:0:0:0:0:1");
            }
        }
    }

    @Test
    void preferredOrderIpv4() throws Exception {
        try (TestDnsServer server = new TestDnsServer(
                ImmutableMap.of(
                        new DefaultDnsQuestion("foo.com.", A),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1")),
                        new DefaultDnsQuestion("foo.com.", AAAA),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1", 1))),
                new DelayHandler(A))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isSuccess);
                assertThat(future.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");
            }
        }
    }

    @Test
    void preferredOrderIpv6() throws Exception {
        try (TestDnsServer server = new TestDnsServer(
                ImmutableMap.of(
                        new DefaultDnsQuestion("foo.com.", A),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1")),
                        new DefaultDnsQuestion("foo.com.", AAAA),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "::1", 1))),
                new DelayHandler(AAAA))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV6_PREFERRED);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isSuccess);
                assertThat(future.getNow().getAddress().getHostAddress()).isEqualTo("0:0:0:0:0:0:0:1");
            }
        }
    }

    private static DnsResolverGroupBuilder builder(TestDnsServer... servers) {
        return builder(true, servers);
    }

    private static DnsResolverGroupBuilder builder(boolean withCacheOption, TestDnsServer... servers) {
        final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                hostname -> DnsServerAddresses.sequential(
                        Stream.of(servers).map(TestDnsServer::addr).collect(toImmutableList())).stream();
        final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                .serverAddressStreamProvider(dnsServerAddressStreamProvider)
                .meterRegistry(PrometheusMeterRegistries.newRegistry())
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .traceEnabled(false);
        if (withCacheOption) {
            builder.dnsCache(NoopDnsCache.INSTANCE);
        }
        return builder;
    }

    private static class TimeoutHandler extends ChannelInboundHandlerAdapter {
        private int recordACount;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                final DatagramDnsQuery dnsQuery = (DatagramDnsQuery) msg;
                final DnsRecord dnsRecord = dnsQuery.recordAt(DnsSection.QUESTION, 0);
                if (dnsRecord.type() == A && recordACount++ == 0) {
                    // Just release the msg and return so that the client request is timed out.
                    ReferenceCountUtil.safeRelease(msg);
                    return;
                }
            }
            super.channelRead(ctx, msg);
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

    private static class DelayHandler extends ChannelInboundHandlerAdapter {
        private final DnsRecordType delayType;

        DelayHandler(DnsRecordType delayType) {
            this.delayType = delayType;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                final DatagramDnsQuery dnsQuery = (DatagramDnsQuery) msg;
                final DnsRecord dnsRecord = dnsQuery.recordAt(DnsSection.QUESTION, 0);
                if (dnsRecord.type() == delayType) {
                    ctx.executor().schedule(() -> {
                        try {
                            super.channelRead(ctx, msg);
                        } catch (Exception ignore) {
                        }
                    }, 1, TimeUnit.SECONDS);
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
