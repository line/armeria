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

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.metric.MoreMeters;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;

/**
 * Tests to verify DNS resolution behavior with connection pooling.
 * This test demonstrates that DNS resolution happens before checking the connection pool,
 * which can be inefficient when pooled connections already exist.
 */
class DnsResolutionWithConnectionPoolTest {

    @Test
    void dnsResolvedBeforePoolLookup() throws Exception {
        // This test uses DNS metrics to count resolution attempts and demonstrates that
        // DNS is resolved for every request, even when a pooled connection exists

        // Start an HTTP server that will actually accept connections
        try (com.linecorp.armeria.server.Server httpServer =
                     com.linecorp.armeria.server.Server.builder()
                                                       .http(0)
                                                       .service("/", (ctx, req) -> com.linecorp.armeria.common.HttpResponse.of(200))
                                                       .build()) {
            httpServer.start().join();
            final int port = httpServer.activeLocalPort();

            try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of(
                    new DefaultDnsQuestion("foo.com.", A),
                    new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1"))
            ))) {
                final MeterRegistry meterRegistry = new SimpleMeterRegistry();
                final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();

                try (ClientFactory factory =
                             ClientFactory.builder()
                                          .domainNameResolverCustomizer(builder -> {
                                              builder.serverAddressStreamProvider(dnsServerList(dnsServer));
                                              builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                              // Disable DNS cache to make the issue more obvious
                                              builder.dnsCache(NoopDnsCache.INSTANCE);
                                          })
                                          .meterRegistry(meterRegistry)
                                          .connectionPoolListener(poolListener)
                                          .build()) {

                    final WebClient client = WebClient.builder()
                                                      .factory(factory)
                                                      .build();

                    final String queryMetricKey =
                            "armeria.client.dns.queries#count{cause=none,name=foo.com.,result=success}";

                    // First request - should resolve DNS and create a connection
                    client.get("http://foo.com:" + port + "/").aggregate().join();

                    await().untilAsserted(() -> {
                        assertThat(MoreMeters.measureAll(meterRegistry))
                                .containsEntry(queryMetricKey, 1.0);
                    });
                    assertThat(poolListener.opened()).isEqualTo(1);

                    // Second request - should reuse connection but currently still resolves DNS
                    client.get("http://foo.com:" + port + "/").aggregate().join();

                    await().untilAsserted(() -> {
                        // This demonstrates the issue: DNS is resolved again (count = 2)
                        // even though we have a pooled connection available
                        assertThat(MoreMeters.measureAll(meterRegistry))
                                .containsEntry(queryMetricKey, 2.0);
                    });
                    // But the connection is reused (count stays at 1)
                    assertThat(poolListener.opened()).isEqualTo(1);

                    // Third request - same pattern continues
                    client.get("http://foo.com:" + port + "/").aggregate().join();

                    await().untilAsserted(() -> {
                        // DNS resolved for the third time
                        assertThat(MoreMeters.measureAll(meterRegistry))
                                .containsEntry(queryMetricKey, 3.0);
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
    }

    @Test
    void dnsResolutionSkippedWhenEndpointHasIpAddress() throws Exception {
        // This test verifies that when using an IP address directly,
        // DNS resolution is skipped entirely as expected

        // Start an HTTP server that will actually accept connections
        try (com.linecorp.armeria.server.Server httpServer =
                     com.linecorp.armeria.server.Server.builder()
                                                       .http(0)
                                                       .service("/", (ctx, req) -> com.linecorp.armeria.common.HttpResponse.of(200))
                                                       .build()) {
            httpServer.start().join();
            final int port = httpServer.activeLocalPort();

            try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of())) {
                final MeterRegistry meterRegistry = new SimpleMeterRegistry();
                final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();

                try (ClientFactory factory =
                             ClientFactory.builder()
                                          .domainNameResolverCustomizer(builder -> {
                                              builder.serverAddressStreamProvider(dnsServerList(dnsServer));
                                              builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
                                              builder.dnsCache(NoopDnsCache.INSTANCE);
                                          })
                                          .meterRegistry(meterRegistry)
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
                    assertThat(MoreMeters.measureAll(meterRegistry))
                            .doesNotContainKey("armeria.client.dns.queries#count");
                    // And connection was created and reused
                    assertThat(poolListener.opened()).isEqualTo(1);
                }
            }
        }
    }

    private static DnsServerAddressStreamProvider dnsServerList(TestDnsServer dnsServer) {
        final InetSocketAddress dnsServerAddr = dnsServer.addr();
        return hostname -> DnsServerAddresses.sequential(ImmutableList.of(dnsServerAddr)).stream();
    }
}

