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
import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.RefreshingAddressResolver.CacheEntry;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddresses;
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
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                InetSocketAddress addr = foo.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.1.1.1");
                assertThat(addr.getPort()).isEqualTo(36462);

                final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.size()).isOne();

                final Future<InetSocketAddress> bar = resolver.resolve(
                        InetSocketAddress.createUnresolved("bar.com", 36462));
                await().untilAsserted(() -> assertThat(bar.isSuccess()).isTrue());
                addr = bar.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.2.3.4");
                assertThat(addr.getPort()).isEqualTo(36462);
                assertThat(cache.size()).isEqualTo(2);

                final Future<InetSocketAddress> foo1 = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 80));
                addr = foo1.getNow();
                assertThat(addr.getAddress().getHostAddress()).isEqualTo("1.1.1.1");
                assertThat(addr.getPort()).isEqualTo(80);
                assertThat(cache.size()).isEqualTo(2);

                final List<InetAddress> addresses =
                        cache.values()
                             .stream()
                             .map(future -> future.join().address())
                             .collect(toImmutableList());
                assertThat(addresses).containsExactlyInAnyOrder(
                        InetAddress.getByAddress("foo.com", new byte[] { 1, 1, 1, 1 }),
                        InetAddress.getByAddress("bar.com", new byte[] { 1, 2, 3, 4 }));
            }
        }
    }

    @Test
    void removedWhenNoCacheHit() throws Exception {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "1.1.1.1", 1))))
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final long start = System.nanoTime();

                final Future<InetSocketAddress> foo = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().untilAsserted(() -> assertThat(foo.isSuccess()).isTrue());
                assertThat(foo.getNow().getAddress().getHostAddress()).isEqualTo("1.1.1.1");

                final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
                await().until(cache::isEmpty);

                assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(
                        (long) (TimeUnit.SECONDS.toNanos(1) * 0.9));
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

                final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.size()).isOne();
                assertThat(cache.get("baz.com").join().address()).isEqualTo(
                        InetAddress.getByAddress("baz.com", new byte[] { 1, 1, 1, 1 }));

                // Resolve one more to increase cache hits.
                resolver.resolve(InetSocketAddress.createUnresolved("baz.com", 36462));

                server.setResponses(ImmutableMap.of(
                        new DefaultDnsQuestion("baz.com.", A),
                        new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("baz.com.", "2.2.2.2"))));

                await().until(() -> {
                    final CompletableFuture<CacheEntry> future = cache.get("baz.com");
                    return future != null && future.join().address().equals(
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

                final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
                await().until(cache::isEmpty);

                assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(
                        (long) (TimeUnit.SECONDS.toNanos(1) * 0.9)); // buffer (90%)

                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);
                assertThat(future.cause()).isExactlyInstanceOf(UnknownHostException.class);
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
            final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
            assertThat(cache.size()).isEqualTo(1);
            final CacheEntry cacheEntry = cache.get("foo.com").join();
            group.close();
            await().until(() -> {
                final ScheduledFuture<?> future = cacheEntry.refreshFuture;
                return future != null && future.isCancelled();
            });
            assertThat(cache).isEmpty();
        }
    }

    @Test
    void negativeTtl() {
        try (TestDnsServer server = new TestDnsServer(ImmutableMap.of())
        ) {
            final EventLoop eventLoop = eventLoopExtension.get();
            final DnsResolverGroupBuilder builder = builder(server).negativeTtl(2);
            try (RefreshingAddressResolverGroup group = builder.build(eventLoop)) {
                final AddressResolver<InetSocketAddress> resolver = group.getResolver(eventLoop);

                final long start = System.nanoTime();

                final Future<InetSocketAddress> future = resolver.resolve(
                        InetSocketAddress.createUnresolved("foo.com", 36462));
                await().until(future::isDone);
                assertThat(future.cause()).isExactlyInstanceOf(UnknownHostException.class);

                final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = group.cache();
                assertThat(cache.size()).isOne();

                await().until(cache::isEmpty);
                assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(
                        (long) (TimeUnit.SECONDS.toNanos(2) * 0.9));
            }
        }
    }

    private static DnsResolverGroupBuilder builder(TestDnsServer server) {
        final DnsServerAddresses addrs = DnsServerAddresses.sequential(server.addr());
        return new DnsResolverGroupBuilder()
                .dnsServerAddressStreamProvider(hostname -> addrs.stream())
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .traceEnabled(false);
    }
}
