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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.AbstractLongAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.AbstractAsyncSelector.ListeningFuture;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsServiceEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsTextEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.testing.BlockingUtils;

class SelectionTimeoutTest {

    private ClientRequestContext ctx;

    @BeforeEach
    void beforeEach() {
        ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    @Test
    void staticEndpointGroup() {
        final Endpoint endpoint1 = Endpoint.of("foo", 8080);
        assertSelectionTimeout(endpoint1).isZero();

        CompletableFuture<Endpoint> result = endpoint1.select(ctx, CommonPools.blockingTaskExecutor());
        assertThat(result).isInstanceOf(UnmodifiableFuture.class)
                          .isCompletedWithValue(endpoint1);

        final Endpoint endpoint2 = Endpoint.of("bar", 8080);
        final EndpointGroup staticEndpoint = EndpointGroup.of(endpoint2, endpoint2);
        assertSelectionTimeout(staticEndpoint).isZero();

        result = endpoint2.select(ctx, CommonPools.blockingTaskExecutor());
        assertThat(result).isInstanceOf(UnmodifiableFuture.class)
                          .isCompletedWithValueMatching(endpoint -> {
                              return ImmutableList.of(endpoint1, endpoint2).contains(endpoint);
                          });
    }

    @Test
    void emptyEndpointGroup() {
        final EndpointGroup staticEndpoint = EndpointGroup.of();
        final CompletableFuture<Endpoint> result =
                staticEndpoint.select(ctx, CommonPools.blockingTaskExecutor());
        assertThat(result.join()).isNull();
    }

    @Test
    void compositeEndpointGroup() {
        final Endpoint endpoint = Endpoint.of("foo", 8080);
        final MockEndpointGroup mockEndpointGroup1 = new MockEndpointGroup(1000);
        final MockEndpointGroup mockEndpointGroup2 = new MockEndpointGroup(2000);
        final EndpointGroup endpointGroup = EndpointGroup.of(endpoint, mockEndpointGroup1, mockEndpointGroup2);

        // The maximum timeout should be used.
        final int expectedTimeout = 2000;
        assertSelectionTimeout(endpointGroup).isEqualTo(expectedTimeout);

        CompletableFuture<Endpoint> result = endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
        assertThat(result).isInstanceOf(UnmodifiableFuture.class)
                          .isCompletedWithValue(endpoint);

        final EndpointGroup composed = EndpointGroup.of(mockEndpointGroup1, mockEndpointGroup2);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        result = composed.select(ctx, CommonPools.blockingTaskExecutor());
        assertThat(result.join()).isNull();
        assertThat(stopwatch.elapsed())
                .isGreaterThanOrEqualTo(Duration.ofMillis(expectedTimeout))
                .isLessThan(Duration.ofMillis(expectedTimeout + 1000));

        stopwatch.reset().start();
        result = composed.select(ctx, CommonPools.blockingTaskExecutor());
        // Update an endpoint after a timeout task is scheduled.
        mockEndpointGroup1.set(endpoint);
        assertThat(result.join()).isEqualTo(endpoint);
        assertThat(stopwatch.elapsed())
                .isLessThan(Duration.ofMillis(expectedTimeout));
    }

    @Test
    void dynamicEndpointGroup_default() {
        try (DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(Flags.defaultConnectTimeoutMillis());
        }
    }

    @Test
    void dynamicEndpointGroup_custom() {
        try (DynamicEndpointGroup endpointGroup = new MockEndpointGroup(1000)) {
            assertSelectionTimeout(endpointGroup).isEqualTo(1000);
        }

        try (DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup(
                EndpointSelectionStrategy.roundRobin(), true, 2000)) {
            assertSelectionTimeout(endpointGroup).isEqualTo(2000);
        }

        try (DynamicEndpointGroup endpointGroup = new MockEndpointGroup(0)) {
            assertSelectionTimeout(endpointGroup).isEqualTo(Long.MAX_VALUE);
        }

        assertThatThrownBy(() -> new MockEndpointGroup(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeoutMillis: -1 (expected: >= 0)");
    }

    @Test
    void dnsAddressEndpointGroup() {
        try (DnsAddressEndpointGroup endpointGroup =
                     DnsAddressEndpointGroup.builder("foo")
                                            .selectionTimeout(Duration.ofSeconds(5))
                                            .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(5000);
        }

        try (DnsAddressEndpointGroup endpointGroup = DnsAddressEndpointGroup.builder("foo")
                                                                            .selectionTimeoutMillis(4000)
                                                                            .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(4000);
        }

        assertThatThrownBy(() -> DnsAddressEndpointGroup.builder("foo")
                                                        .selectionTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeoutMillis: -1 (expected: >= 0)");

        assertThatThrownBy(() -> DnsAddressEndpointGroup.builder("foo")
                                                        .selectionTimeout(Duration.ofMillis(-2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeout: PT-0.002S (expected: >= 0)");
    }

    @Test
    void dnsTextEndpointGroup() {
        try (DnsTextEndpointGroup endpointGroup =
                     DnsTextEndpointGroup.builder("foo", bytes -> Endpoint.of(new String(bytes)))
                                         .selectionTimeout(Duration.ofSeconds(5))
                                         .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(5000);
        }

        try (DnsTextEndpointGroup endpointGroup =
                     DnsTextEndpointGroup.builder("foo", bytes -> Endpoint.of(new String(bytes)))
                                         .selectionTimeoutMillis(4000)
                                         .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(4000);
        }

        assertThatThrownBy(() -> DnsTextEndpointGroup.builder("foo", bytes -> Endpoint.of(new String(bytes)))
                                                     .selectionTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeoutMillis: -1 (expected: >= 0)");

        assertThatThrownBy(() -> DnsTextEndpointGroup.builder("foo", bytes -> Endpoint.of(new String(bytes)))
                                                     .selectionTimeout(Duration.ofMillis(-2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeout: PT-0.002S (expected: >= 0)");
    }

    @Test
    void dnsServiceEndpointGroup() {
        try (DnsServiceEndpointGroup endpointGroup =
                     DnsServiceEndpointGroup.builder("foo")
                                            .selectionTimeout(Duration.ofSeconds(6))
                                            .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(6000);
        }

        try (DnsServiceEndpointGroup endpointGroup =
                     DnsServiceEndpointGroup.builder("foo")
                                            .selectionTimeoutMillis(3000)
                                            .build()) {
            assertSelectionTimeout(endpointGroup).isEqualTo(3000);
        }

        assertThatThrownBy(() -> DnsServiceEndpointGroup.builder("foo")
                                                        .selectionTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeoutMillis: -1 (expected: >= 0)");

        assertThatThrownBy(() -> DnsServiceEndpointGroup.builder("foo")
                                                        .selectionTimeout(Duration.ofMillis(-2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionTimeout: PT-0.002S (expected: >= 0)");
    }

    @Test
    void orElseEndpointGroup() {
        try (DynamicEndpointGroup first = new MockEndpointGroup(1000);
             DynamicEndpointGroup second = new MockEndpointGroup(2000)) {
            assertSelectionTimeout(new OrElseEndpointGroup(first, second)).isEqualTo(2000);
        }
    }

    @Test
    void healthCheckedEndpointGroup_default() {
        try (DynamicEndpointGroup delegate = new MockEndpointGroup(1000);
             HealthCheckedEndpointGroup healthGroup = HealthCheckedEndpointGroup.of(delegate, "/health")) {
            assertSelectionTimeout(healthGroup).isEqualTo(1000 + Flags.defaultResponseTimeoutMillis());
        }
    }

    @Test
    void healthCheckedEndpointGroup_custom() {
        try (DynamicEndpointGroup delegate = new MockEndpointGroup(1000);
             HealthCheckedEndpointGroup healthGroup =
                     HealthCheckedEndpointGroup.builder(delegate, "/health")
                                               .selectionTimeout(Duration.ofSeconds(8))
                                               .build()) {
            assertSelectionTimeout(healthGroup).isEqualTo(9000);
        }

        try (DynamicEndpointGroup delegate = new MockEndpointGroup(3000);
             HealthCheckedEndpointGroup healthGroup =
                     HealthCheckedEndpointGroup.builder(delegate, "/health")
                                               .selectionTimeoutMillis(7000)
                                               .build()) {
            assertSelectionTimeout(healthGroup).isEqualTo(10000);
        }

        final DynamicEndpointGroup delegate = new MockEndpointGroup(1000);
        assertThatThrownBy(() -> {
            HealthCheckedEndpointGroup.builder(delegate, "/health")
                                      .selectionTimeout(Duration.ofMillis(-1));
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("selectionTimeoutMillis: -1 (expected: >= 0)");

        assertThatThrownBy(() -> {
            HealthCheckedEndpointGroup.builder(delegate, "/health")
                                      .selectionTimeoutMillis(-2);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("selectionTimeoutMillis: -2 (expected: >= 0)");
        delegate.close();
    }

    @Test
    void select_timeout() {
        final int expectedTimeout = 3000;
        try (MockEndpointGroup endpointGroup = new MockEndpointGroup(expectedTimeout)) {
            assertSelectionTimeout(endpointGroup).isEqualTo(expectedTimeout);

            final Stopwatch stopwatch = Stopwatch.createStarted();
            final CompletableFuture<Endpoint> result =
                    endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
            assertThat(result.join()).isNull();
            assertThat(stopwatch.elapsed())
                    .isGreaterThanOrEqualTo(Duration.ofMillis(expectedTimeout))
                    .isLessThan(Duration.ofMillis(expectedTimeout + 2000));
        }
    }

    @Test
    void select_success() {
        final Endpoint endpoint = Endpoint.of("foo", 8080);
        final MockEndpointGroup endpointGroup = new MockEndpointGroup(2000);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final CompletableFuture<Endpoint> result =
                endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
        // Update an endpoint after a timeout task is scheduled.
        endpointGroup.set(endpoint);
        assertThat(result.join()).isEqualTo(endpoint);
        assertThat(stopwatch.elapsed()).isLessThan(Duration.ofMillis(2000));
    }

    @Test
    void select_shouldRespectResponseTimeout() {
        final CompletableFuture<?> future = CompletableFuture.supplyAsync(() -> {
            final Stopwatch stopwatch = Stopwatch.createStarted();
            ctx.setResponseTimeout(Duration.ofSeconds(2));
            try (MockEndpointGroup endpointGroup = new MockEndpointGroup(5000)) {
                final CompletableFuture<Endpoint> result =
                        endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
                assertThat(BlockingUtils.blockingRun(result::join)).isNull();
                assertThat(stopwatch.elapsed())
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
            }
            return null;
        }, ctx.eventLoop());
        future.join();
    }

    @Test
    void select_unlimited() {
        final CompletableFuture<?> future = CompletableFuture.supplyAsync(() -> {
            ctx.clearResponseTimeout();
            try (MockEndpointGroup endpointGroup = new MockEndpointGroup(Long.MAX_VALUE)) {
                final ListeningFuture result =
                        (ListeningFuture) endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
                // No timeout is scheduled
                assertThat((Future<?>) result.timeoutFuture()).isNull();
                final Endpoint endpoint = Endpoint.of("foo", 8080);
                endpointGroup.set(endpoint);
            }
            return null;
        }, ctx.eventLoop());
        future.join();
    }

    @Test
    void selectorSelectionTimeout() {
        try (DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup(true, 4000)) {
            assertSelectionTimeout(endpointGroup).isEqualTo(4000);
            final ClientRequestContext ctx =
                    ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                        .requestOptions(RequestOptions.builder()
                                                                      .responseTimeoutMillis(1000)
                                                                      .build())
                                        .build();
            final Stopwatch timer = Stopwatch.createStarted();
            endpointGroup.select(ctx, CommonPools.blockingTaskExecutor()).join();
            final long elapsed = timer.stop().elapsed(TimeUnit.MILLISECONDS);
            // Should complete after the selection timeout.
            assertThat(elapsed).isGreaterThanOrEqualTo(2000);
            assertThat(ClientPendingThrowableUtil.pendingThrowable(ctx))
                    .isInstanceOf(EndpointSelectionTimeoutException.class)
                    .hasMessageContaining("Failed to select within 4000 ms an endpoint from");
        }
    }

    private static AbstractLongAssert<?> assertSelectionTimeout(EndpointGroup endpointGroup) {
        return assertThat(endpointGroup.selectionTimeoutMillis());
    }

    static final class MockEndpointGroup extends DynamicEndpointGroup {
        MockEndpointGroup(long selectionTimeoutMillis) {
            super(true, selectionTimeoutMillis);
        }

        void set(Endpoint... endpoints) {
            setEndpoints(ImmutableList.copyOf(endpoints));
        }
    }
}
