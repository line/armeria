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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
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
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.assertj.core.api.AbstractIntegerAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.RefreshingAddressResolver.CacheEntry;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.internal.client.dns.ByteArrayDnsRecord;
import com.linecorp.armeria.internal.client.dns.DnsQuestionWithoutTrailingDot;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsErrorCauseException;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;

class RefreshingAddressResolverTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

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
                assertIpAddress(foo).isEqualTo("1.1.1.1");
                assertHostAddress(foo).isEqualTo("1.1.1.1");
                assertPort(foo).isEqualTo(36462);

                final Cache<String, CacheEntry> cache = group.cache();
                assertThat(cache.estimatedSize()).isOne();

                final Future<InetSocketAddress> bar = resolver.resolve(
                        InetSocketAddress.createUnresolved("bar.com", 36462));
                assertHostAddress(bar).isEqualTo("1.2.3.4");
                assertPort(bar).isEqualTo(36462);
                assertThat(cache.estimatedSize()).isEqualTo(2);

                final Future<InetSocketAddress> foo1 = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 80));
                assertHostAddress(foo1).isEqualTo("1.1.1.1");
                assertPort(foo1).isEqualTo(80);
                assertThat(cache.estimatedSize()).isEqualTo(2);

                final List<InetAddress> addresses =
                        cache.asMap()
                             .values()
                             .stream()
                             .map(CacheEntry::address)
                             .collect(toImmutableList());
                assertThat(addresses).containsExactlyInAnyOrder(
                        InetAddress.getByAddress("foo.com", new byte[] { 1, 1, 1, 1 }),
                        InetAddress.getByAddress("bar.com", new byte[] { 1, 2, 3, 4 }));
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
                assertHostAddress(foo).isEqualTo("1.1.1.1");

                Thread.sleep(1100); // wait until refresh cache entry (ttl + a)

                final Cache<String, CacheEntry> cache = group.cache();
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
                assertHostAddress(foo).isEqualTo("1.1.1.1");

                final Cache<String, CacheEntry> cache = group.cache();
                assertThat(cache.estimatedSize()).isOne();
                assertThat(cache.getIfPresent("baz.com").address()).isEqualTo(
                        InetAddress.getByAddress("baz.com", new byte[] { 1, 1, 1, 1 }));

                // Resolve one more to increase cache hits.
                resolver.resolve(InetSocketAddress.createUnresolved("baz.com", 36462));

                server.setResponses(ImmutableMap.of(
                        new DefaultDnsQuestion("baz.com.", A),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "2.2.2.2"))));

                await().until(() -> {
                    final CacheEntry entry = cache.getIfPresent("baz.com");
                    return entry != null && entry.address().equals(
                            InetAddress.getByAddress("baz.com", new byte[] { 2, 2, 2, 2 }));
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
            builder.autoRefreshBackoff(Backoff.ofDefault().withMaxAttempts(1));
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final long start = System.nanoTime();

                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                assertHostAddress(foo).isEqualTo("1.1.1.1");

                server.setResponses(ImmutableMap.of());

                // Schedule resolve() every 500 millis to keep cache hits greater than 0.
                for (int i = 1; i <= 4; i++) {
                    eventLoop.schedule(
                            () -> resolver.resolve(InetSocketAddress.createUnresolved("foo.com", 36462)),
                            500 * i, TimeUnit.MILLISECONDS);
                }

                final Cache<String, CacheEntry> cache = group.cache();
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

            final Cache<String, CacheEntry> cache = group.cache();
            assertThat(cache.estimatedSize()).isOne();
            final CacheEntry cacheEntry = cache.getIfPresent("foo.com");
            assertThat(cacheEntry).isNotNull();
            group.close();
            assertThat(cache.estimatedSize()).isZero();
        }
    }

    @Test
    void negativeTtl() {
        // TimeoutHandler times out only the first query.
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(), new TimeoutHandler())) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder =
                    builder(false, server).negativeTtl(600)
                                          .queryTimeoutMillis(1000);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);

                final Throwable cause = future.cause();
                assertDnsTimeoutException(cause);

                // Because it's timed out, the result is not cached.
                final Cache<String, CacheEntry> cache = group.cache();
                assertThat(cache.estimatedSize())
                        .as("cache should be empty. entries: %s",
                            cache.asMap().entrySet().stream()
                                 .collect(toImmutableMap(Entry::getKey, Entry::getValue)))
                        .isZero();

                final Future<InetSocketAddress> future2 = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future2::isDone);
                assertThat(future2.cause()).isInstanceOf(UnknownHostException.class)
                                           .hasCauseInstanceOf(DnsErrorCauseException.class);
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
                assertHostAddress(future).isEqualTo("0:0:0:0:0:0:0:1");
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
                assertHostAddress(future).isEqualTo("1.1.1.1");
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
                assertHostAddress(future).isEqualTo("0:0:0:0:0:0:0:1");
            }
        }
    }

    @Test
    void shouldRefreshWhenDnsCacheIsExpired() throws ExecutionException, InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))
        ) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder().build();
            final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                    hostname -> DnsServerAddresses.sequential(
                            Stream.of(server).map(TestDnsServer::addr).collect(toImmutableList())).stream();
            final RefreshingAddressResolverGroup group = new DnsResolverGroupBuilder()
                    .serverAddressStreamProvider(dnsServerAddressStreamProvider)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    .dnsCache(dnsCache)
                    .build(eventLoop);
            final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
            final Cache<String, CacheEntry> cache = group.cache();

            final Future<InetSocketAddress> foo0 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            await().untilAsserted(() -> assertThat(foo0.isSuccess()).isTrue());
            final InetSocketAddress fooAddress0 = foo0.get();
            final CacheEntry fooCache0 = cache.getIfPresent("foo.com");

            final Future<InetSocketAddress> foo1 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            final InetSocketAddress fooAddress1 = foo1.get();
            assertThat(fooAddress1).isEqualTo(fooAddress0);
            final CacheEntry fooCache1 = cache.getIfPresent("foo.com");
            assertThat(fooCache1.address()).isSameAs(fooCache0.address());

            assertThat(cache.estimatedSize()).isOne();
            final CacheEntry cacheEntry = cache.getIfPresent("foo.com");
            assertThat(cacheEntry).isNotNull();
            cache.invalidateAll();

            final Future<InetSocketAddress> foo2 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            await().untilAsserted(() -> assertThat(foo2.isSuccess()).isTrue());
            final InetSocketAddress fooAddress2 = foo2.get();
            final CacheEntry fooCache2 = cache.getIfPresent("foo.com");
            // The address has been refreshed.
            assertThat(fooCache2.address()).isNotSameAs(fooCache1.address());
            assertThat(fooAddress2).isEqualTo(fooAddress1);
            group.close();
        }
    }

    @Test
    void shouldRefreshWhenSearchDnsCacheIsExpired() throws InterruptedException, ExecutionException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1")),
                new DefaultDnsQuestion("foo.com.armeria.dev", A),
                new DefaultDnsResponse(0)
                        .addRecord(ANSWER, newAddressRecord("foo.com.armeria.dev", "2.2.2.2"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder().build();

            // Mock cache data
            final DnsQuestionWithoutTrailingDot barQuestion = DnsQuestionWithoutTrailingDot.of("bar.com.", A);
            dnsCache.cache(barQuestion, ByteArrayDnsRecord.copyOf(newAddressRecord("bar.com.", "3.3.3.3")));

            final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                    hostname -> DnsServerAddresses.sequential(
                            Stream.of(server).map(TestDnsServer::addr).collect(toImmutableList())).stream();

            final RefreshingAddressResolverGroup group = new DnsResolverGroupBuilder()
                    .serverAddressStreamProvider(dnsServerAddressStreamProvider)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    .searchDomains("armeria.dev")
                    // Make foo.com.aremeria.dev take precedence.
                    .ndots(2)
                    .dnsCache(dnsCache)
                    .build(eventLoop);
            final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
            final Cache<String, CacheEntry> cache = group.cache();

            final Future<InetSocketAddress> fooWithTrailingDot0 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com.", 36462));
            assertIpAddress(fooWithTrailingDot0).isEqualTo("1.1.1.1");
            final CacheEntry fooWithTrailingDotCache0 = cache.getIfPresent("foo.com.");
            assertThat(cache.estimatedSize()).isOne();

            final Future<InetSocketAddress> foo0 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            // Should resolve search domain IP
            assertIpAddress(foo0).isEqualTo("2.2.2.2");
            final InetSocketAddress fooAddress0 = foo0.get();

            final CacheEntry fooCache0 = cache.getIfPresent("foo.com");
            final Future<InetSocketAddress> foo1 = resolver.resolve(
                    InetSocketAddress.createUnresolved("foo.com", 36462));
            final InetSocketAddress fooAddress1 = foo1.get();
            assertThat(fooAddress1).isEqualTo(fooAddress0);
            final CacheEntry fooCache1 = cache.getIfPresent("foo.com");
            assertThat(fooCache1.address()).isSameAs(fooCache0.address());
            assertThat(cache.estimatedSize()).isEqualTo(2);

            dnsCache.remove(barQuestion);
            // Wait until the removal event is delivered.
            Thread.sleep(2000);

            // Unrelated cache is removed. Should not refresh the cached values.
            final CacheEntry fooWithTrailingDotCache1 = cache.getIfPresent("foo.com.");
            assertThat(fooWithTrailingDotCache1.address()).isSameAs(fooWithTrailingDotCache0.address());
            final CacheEntry fooCache2 = cache.getIfPresent("foo.com");
            assertThat(fooCache2.address()).isSameAs(fooCache0.address());

            final DnsQuestionWithoutTrailingDot fooWithTrailingDotQuestion =
                    DnsQuestionWithoutTrailingDot.of("foo.com.", A);
            dnsCache.remove(fooWithTrailingDotQuestion);
            // Wait until the removal event is delivered.
            Thread.sleep(2000);
            final CacheEntry fooWithTrailingDotCache3 = cache.getIfPresent("foo.com.");
            // The address has been refreshed.
            assertThat(fooWithTrailingDotCache3.address()).isNotSameAs(fooWithTrailingDotCache0.address());
            assertThat(fooWithTrailingDotCache3.address()).isEqualTo(fooWithTrailingDotCache0.address());

            final DnsQuestionWithoutTrailingDot fooQuestion =
                    DnsQuestionWithoutTrailingDot.of("foo.com", "foo.com.armeria.dev.", A);
            dnsCache.remove(fooQuestion);
            // Wait until the removal event is delivered.
            Thread.sleep(2000);
            final CacheEntry fooCache3 = cache.getIfPresent("foo.com");
            // The address has been refreshed.
            assertThat(fooCache3.address()).isNotSameAs(fooCache0.address());
            assertThat(fooCache3.address()).isEqualTo(fooCache0.address());
            group.close();
        }
    }

    @Test
    void disableAutoRefresh() throws InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder().build();
            try (RefreshingAddressResolverGroup group = builder(false, server).enableAutoRefresh(false)
                                                                              .dnsCache(dnsCache)
                                                                              .build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicBoolean removed = new AtomicBoolean();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removed.set(true);
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {}
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> fooAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com.", 36462));
                assertIpAddress(fooAddr).isEqualTo("1.1.1.1");
                assertThat(cache.estimatedSize()).isOne();

                final DnsQuestionWithoutTrailingDot fooQuestion =
                        DnsQuestionWithoutTrailingDot.of("foo.com.", A);
                dnsCache.remove(fooQuestion);
                // Wait until the removal event is delivered.
                await().untilTrue(removed);

                // Should not refresh the cached value.
                final CacheEntry fooCache = cache.getIfPresent("foo.com.");
                assertThat(fooCache).isNull();
            }
        }
    }

    @Test
    void autoRefreshTimeoutFunction() throws InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("static.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("static.com.", "1.1.1.1")),
                new DefaultDnsQuestion("dynamic.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("dynamic.com.", "2.2.2.2"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder()
                                              .executor(eventLoop)
                                              .build();
            try (RefreshingAddressResolverGroup group =
                         builder(false, server)
                                 // Refresh only 'static.com' domain
                                 .autoRefreshTimeout(hostname -> {
                                     if ("static.com.".equals(hostname)) {
                                         return 60 * 1000;
                                     }
                                     // Don't refresh
                                     return 0;
                                 })
                                 .dnsCache(dnsCache)
                                 .build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicInteger removalCounter = new AtomicInteger();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removalCounter.incrementAndGet();
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {}
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> staticAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("static.com.", 36462));
                assertIpAddress(staticAddr).isEqualTo("1.1.1.1");
                assertThat(cache.estimatedSize()).isOne();

                final Future<InetSocketAddress> dynamicAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("dynamic.com.", 36462));
                assertIpAddress(dynamicAddr).isEqualTo("2.2.2.2");
                assertThat(cache.estimatedSize()).isEqualTo(2);

                final CacheEntry oldStaticCacheEntry = cache.getIfPresent("static.com.");

                server.setResponses(
                        ImmutableMap.of(
                                new DefaultDnsQuestion("static.com.", A),
                                new DefaultDnsResponse(0).addRecord(ANSWER,
                                                                    newAddressRecord("static.com.", "1.1.1.2")),
                                new DefaultDnsQuestion("dynamic.com.", A),
                                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("dynamic.com.",
                                                                                             "2.2.2.3"))));

                final DnsQuestionWithoutTrailingDot staticQuestion =
                        DnsQuestionWithoutTrailingDot.of("static.com.", "static.com.", A);
                final DnsQuestionWithoutTrailingDot dynamicQuestion =
                        DnsQuestionWithoutTrailingDot.of("dynamic.com.", "dynamic.com.", A);
                dnsCache.remove(staticQuestion);
                dnsCache.remove(dynamicQuestion);
                // Wait until the removal event is delivered.
                await().untilAtomic(removalCounter, Matchers.equalTo(2));

                // Wait for the refresh task to be done.
                Thread.sleep(2000);

                final CacheEntry newStaticCacheEntry = cache.getIfPresent("static.com.");
                final CacheEntry newDynamicCacheEntry = cache.getIfPresent("dynamic.com.");
                assertThat(newStaticCacheEntry.address().getAddress())
                        .isEqualTo(NetUtil.createByteArrayFromIpAddressString("1.1.1.2"));
                // New CacheEntry should inherit the original creationTimeNanos
                assertThat(oldStaticCacheEntry.originalCreationTimeNanos())
                        .isEqualTo(newStaticCacheEntry.originalCreationTimeNanos());
                assertThat(newDynamicCacheEntry).isNull();
            }
        }
    }

    @Test
    void autoRefreshTimeout() throws InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder()
                                              .executor(eventLoop)
                                              .build();
            try (RefreshingAddressResolverGroup group =
                         builder(false, server)
                                 .autoRefreshTimeoutMillis(5 * 1000) // refresh for 5 seconds
                                 .dnsCache(dnsCache)
                                 .build(eventLoop)) {

                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicInteger removalCounter = new AtomicInteger();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removalCounter.incrementAndGet();
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {}
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> fooAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com.", 36462));
                assertIpAddress(fooAddr).isEqualTo("1.1.1.1");

                assertThat(cache.estimatedSize()).isOne();
                final DnsQuestionWithoutTrailingDot fooQuestion =
                        DnsQuestionWithoutTrailingDot.of("foo.com.", A);
                dnsCache.remove(fooQuestion);
                await().untilAtomic(removalCounter, Matchers.equalTo(1));

                // Wait for the refresh task to be done.
                Thread.sleep(2000);

                CacheEntry fooCacheEntry = cache.getIfPresent("foo.com.");
                assertThat(fooCacheEntry.address().getAddress())
                        .isEqualTo(NetUtil.createByteArrayFromIpAddressString("1.1.1.1"));

                // Wait until the refresh timeout passes.
                Thread.sleep(5000);
                dnsCache.remove(fooQuestion);
                // Wait for the refresh task to be done.
                Thread.sleep(2000);
                fooCacheEntry = cache.getIfPresent("foo.com.");
                assertThat(fooCacheEntry).isNull();
            }
        }
    }

    @Test
    void zeroAutoRefreshTimeout() throws InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder()
                                              .executor(eventLoop)
                                              .build();
            try (RefreshingAddressResolverGroup group =
                         builder(false, server)
                                 .autoRefreshTimeout(hostname -> 0)
                                 .dnsCache(dnsCache)
                                 .build(eventLoop)) {

                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicInteger removalCounter = new AtomicInteger();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removalCounter.incrementAndGet();
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {}
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> fooAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com.", 36462));
                assertIpAddress(fooAddr).isEqualTo("1.1.1.1");

                assertThat(cache.estimatedSize()).isOne();
                final DnsQuestionWithoutTrailingDot fooQuestion =
                        DnsQuestionWithoutTrailingDot.of("foo.com.", A);
                dnsCache.remove(fooQuestion);
                await().untilAtomic(removalCounter, Matchers.equalTo(1));

                // Wait for the refresh task to be done.
                Thread.sleep(2000);

                final CacheEntry fooCacheEntry = cache.getIfPresent("foo.com.");
                // Should not refresh if the timeout is zero.
                assertThat(fooCacheEntry).isNull();
            }
        }
    }

    @Test
    void autoRefreshTimeoutWithFailedQuery() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder()
                                              .executor(eventLoop)
                                              .build();
            try (RefreshingAddressResolverGroup group =
                         builder(false, server)
                                 .autoRefreshTimeoutMillis(5 * 1000) // refresh for 5 seconds
                                 .dnsCache(dnsCache)
                                 .build(eventLoop)) {

                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicInteger removalCounter = new AtomicInteger();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removalCounter.incrementAndGet();
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {}
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> fooAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com.", 36462));
                assertIpAddress(fooAddr).isEqualTo("1.1.1.1");

                // Make a failed query
                server.setResponses(ImmutableMap.of());

                assertThat(cache.estimatedSize()).isOne();
                final DnsQuestionWithoutTrailingDot fooQuestion =
                        DnsQuestionWithoutTrailingDot.of("foo.com.", A);
                dnsCache.remove(fooQuestion);
                await().untilAtomic(removalCounter, Matchers.equalTo(1));

                // Wait for the refresh task to be done.
                Thread.sleep(2000);

                // DnsCache should not be filled.
                assertThat(dnsCache.get(fooQuestion)).isNull();

                CacheEntry fooCacheEntry = cache.getIfPresent("foo.com.");
                assertThat(fooCacheEntry.address().getAddress())
                        .isEqualTo(NetUtil.createByteArrayFromIpAddressString("1.1.1.1"));

                // Wait until the refresh timeout passes.
                Thread.sleep(10000);
                // Wait for the refresh task to be done.
                Thread.sleep(2000);
                fooCacheEntry = cache.getIfPresent("foo.com.");
                assertThat(fooCacheEntry).isNull();
            }
        }
    }

    @Test
    void shouldStopRefreshingOnEviction() throws InterruptedException {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1")),
                new DefaultDnsQuestion("bar.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("bar.com.", "2.2.2.2")),
                new DefaultDnsQuestion("baz.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "3.3.3.3"))))) {

            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsCache dnsCache = DnsCache.builder()
                                              .executor(eventLoop)
                                              .cacheSpec("maximumSize=2")
                                              .build();
            try (RefreshingAddressResolverGroup group =
                         builder(false, server)
                                 .dnsCache(dnsCache)
                                 .build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);
                final AtomicInteger removalCounter = new AtomicInteger();
                final AtomicInteger evictionCounter = new AtomicInteger();
                dnsCache.addListener(new DnsCacheListener() {
                    @Override
                    public void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                                          @Nullable UnknownHostException cause) {
                        removalCounter.incrementAndGet();
                    }

                    @Override
                    public void onEviction(DnsQuestion question, @Nullable List<DnsRecord> records,
                                           @Nullable UnknownHostException cause) {
                        // The old cache should be evicted first.
                        assertThat(((DnsQuestionWithoutTrailingDot) question).originalName())
                                .isEqualTo("foo.com.");
                        evictionCounter.incrementAndGet();
                    }
                });

                final Cache<String, CacheEntry> cache = group.cache();

                final Future<InetSocketAddress> fooAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com.", 36462));
                assertIpAddress(fooAddr).isEqualTo("1.1.1.1");
                assertThat(cache.estimatedSize()).isOne();

                final Future<InetSocketAddress> barAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("bar.com.", 36462));
                assertIpAddress(barAddr).isEqualTo("2.2.2.2");

                final Future<InetSocketAddress> bazAddr = resolver.resolve(
                        InetSocketAddress.createUnresolved("baz.com.", 36462));
                assertIpAddress(bazAddr).isEqualTo("3.3.3.3");
                await().atMost(Duration.ofSeconds(20))
                       .untilAtomic(evictionCounter, Matchers.equalTo(1));
                assertThat(removalCounter).hasValue(0);

                // Wait for the eviction event to be delivered.
                Thread.sleep(2000);
                assertThat(cache.estimatedSize()).isEqualTo(2);
                assertThat(cache.asMap().keySet())
                        .containsExactlyInAnyOrder("bar.com.", "baz.com.");
            }
        }
    }

    private static AbstractStringAssert<?> assertIpAddress(Future<InetSocketAddress> staticAddr) {
        await().untilAsserted(() -> assertThat(staticAddr.isSuccess()).isTrue());
        return assertThat(NetUtil.bytesToIpAddress(staticAddr.getNow().getAddress().getAddress()));
    }

    private static AbstractStringAssert<?> assertHostAddress(Future<InetSocketAddress> staticAddr) {
        await().untilAsserted(() -> assertThat(staticAddr.isSuccess()).isTrue());
        return assertThat(staticAddr.getNow().getAddress().getHostAddress());
    }

    private static AbstractIntegerAssert<?> assertPort(Future<InetSocketAddress> staticAddr) {
        await().untilAsserted(() -> assertThat(staticAddr.isSuccess()).isTrue());
        return assertThat(staticAddr.getNow().getPort());
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
            builder.dnsCache(DnsCache.builder().build());
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
