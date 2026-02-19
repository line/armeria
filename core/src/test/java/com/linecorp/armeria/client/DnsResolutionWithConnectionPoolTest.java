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
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;

/**
 * Tests to verify DNS resolution behavior with connection pooling.
 * This test demonstrates that DNS resolution happens before checking the connection pool,
 * which can be inefficient when pooled connections already exist.
 */
class DnsResolutionWithConnectionPoolTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void dnsResolvedBeforePoolLookup() throws Exception {
        // This test uses a DNS observer to count resolution attempts and demonstrates that
        // DNS is resolved for every request, even when a pooled connection exists

        final int port = server.httpPort();

        try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1"))
        ))) {
            final CountingDnsQueryObserverFactory dnsObserver = new CountingDnsQueryObserverFactory();
            final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();

            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.serverAddressStreamProvider(dnsServerList(dnsServer));
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                          // Disable DNS caches to make the issue more obvious
                                          // This disables both the DnsCache and the address resolver cache
                                          builder.cacheSpec("maximumSize=0");
                                          // Use our custom observer to track DNS queries directly
                                          builder.dnsQueryLifecycleObserverFactory(dnsObserver);
                                      })
                                      .connectionPoolListener(poolListener)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                // First request - should resolve DNS and create a connection
                client.get("http://foo.com:" + port + "/").aggregate().join();

                await().untilAsserted(() -> {
                    assertThat(dnsObserver.queryCount()).isEqualTo(1);
                });
                assertThat(poolListener.opened()).isEqualTo(1);

                // Second request - should reuse connection but currently still resolves DNS
                client.get("http://foo.com:" + port + "/").aggregate().join();

                await().untilAsserted(() -> {
                    // This demonstrates the issue: DNS is resolved again (count = 2)
                    // even though we have a pooled connection available
                    assertThat(dnsObserver.queryCount()).isEqualTo(2);
                });
                // But the connection is reused (count stays at 1)
                assertThat(poolListener.opened()).isEqualTo(1);

                // Third request - same pattern continues
                client.get("http://foo.com:" + port + "/").aggregate().join();

                await().untilAsserted(() -> {
                    // DNS resolved for the third time
                    assertThat(dnsObserver.queryCount()).isEqualTo(3);
                });
                // Connection still reused
                assertThat(poolListener.opened()).isEqualTo(1);

                // The inefficiency: We're resolving DNS 3 times for 3 requests
                // even though we only created 1 connection that could be reused.
                // This happens because HttpClientDelegate.execute() calls resolveAddress()
                // BEFORE checking if there's a pooled connection available.
            }
        }
    }

    @Test
    void dnsResolutionSkippedWhenEndpointHasIpAddress() throws Exception {
        // This test verifies that when using an IP address directly,
        // DNS resolution is skipped entirely as expected

        final int port = server.httpPort();

        try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of())) {
            final CountingDnsQueryObserverFactory dnsObserver = new CountingDnsQueryObserverFactory();
            final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();

            try (ClientFactory factory =
                         ClientFactory.builder()
                                      .domainNameResolverCustomizer(builder -> {
                                          builder.serverAddressStreamProvider(dnsServerList(dnsServer));
                                          builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                          builder.cacheSpec("maximumSize=0");
                                          builder.dnsQueryLifecycleObserverFactory(dnsObserver);
                                      })
                                      .connectionPoolListener(poolListener)
                                      .build()) {

                final WebClient client = WebClient.builder()
                                                  .factory(factory)
                                                  .build();

                // Use an IP address directly - should skip DNS resolution entirely
                client.get("http://127.0.0.1:" + port + "/").aggregate().join();
                client.get("http://127.0.0.1:" + port + "/").aggregate().join();
                client.get("http://127.0.0.1:" + port + "/").aggregate().join();

                // Verify no DNS queries were made (because endpoint.hasIpAddr() returns true)
                assertThat(dnsObserver.queryCount()).isEqualTo(0);
                // And connection was created and reused
                assertThat(poolListener.opened()).isEqualTo(1);
            }
        }
    }

    private static DnsServerAddressStreamProvider dnsServerList(TestDnsServer dnsServer) {
        final InetSocketAddress dnsServerAddr = dnsServer.addr();
        return hostname -> DnsServerAddresses.sequential(ImmutableList.of(dnsServerAddr)).stream();
    }

    /**
     * A test observer that tracks DNS query events directly without using metrics.
     */
    private static class CountingDnsQueryObserverFactory implements DnsQueryLifecycleObserverFactory {
        private final AtomicInteger successfulQueries = new AtomicInteger(0);

        @Override
        public DnsQueryLifecycleObserver newDnsQueryLifecycleObserver(DnsQuestion question) {
            return new DnsQueryLifecycleObserver() {
                @Override
                public void queryWritten(InetSocketAddress dnsServerAddress, ChannelFuture future) {}

                @Override
                public void queryCancelled(int queriesRemaining) {}

                @Override
                public DnsQueryLifecycleObserver queryRedirected(List<InetSocketAddress> nameServers) {
                    return this;
                }

                @Override
                public DnsQueryLifecycleObserver queryCNAMEd(DnsQuestion cnameQuestion) {
                    return this;
                }

                @Override
                public DnsQueryLifecycleObserver queryNoAnswer(DnsResponseCode code) {
                    return this;
                }

                @Override
                public void queryFailed(Throwable cause) {}

                @Override
                public void querySucceed() {
                    successfulQueries.incrementAndGet();
                }
            };
        }

        int queryCount() {
            return successfulQueries.get();
        }
    }
}

