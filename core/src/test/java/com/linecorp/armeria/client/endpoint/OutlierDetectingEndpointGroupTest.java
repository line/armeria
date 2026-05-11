/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutlierDetectingEndpointGroupTest {

    @Test
    void whenReadyCompletesAfterDelegateBecomesReady() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        assertThat(endpointGroup.whenReady()).isNotDone();
        delegate.update(ImmutableList.of(Endpoint.of("foo.com")));
        assertThat(endpointGroup.whenReady()).isCompletedWithValueMatching(
                endpoints -> endpoints.contains(Endpoint.of("foo.com")));
        assertThat(endpointGroup.endpoints()).containsExactly(Endpoint.of("foo.com"));
    }

    @Test
    void listenerReceivesUpdatedEndpoints() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();
        final AtomicReference<List<Endpoint>> captor = new AtomicReference<>();
        endpointGroup.addListener(captor::set);

        delegate.update(ImmutableList.of(Endpoint.of("foo.com")));

        // Listener notification is dispatched asynchronously on the executor.
        await().untilAsserted(() ->
                assertThat(captor.get()).containsExactly(Endpoint.of("foo.com")));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Spec — strict mode (maxEndpointAge=-1, default):
    //   pool = delegate.endpoints() − bad endpoints, capped at maxNumEndpoints.
    // Spec — keep-alive mode (maxEndpointAge>0, opt-in):
    //   pool tries to fill maxNumEndpoints from delegate.endpoints() − bad first; if it can't, the
    //   remainder is filled from the cache (still-valid old endpoints).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void strictMode_poolMirrorsDelegate() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        // Default: maxNumEndpoints=1024, maxEndpointAge=-1 (strict).
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        final Endpoint a = Endpoint.of("a.com");
        final Endpoint b = Endpoint.of("b.com");
        final Endpoint c = Endpoint.of("c.com");

        delegate.update(ImmutableList.of(a, b, c));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(a, b, c);

        // Delegate drops B. Strict mode tracks delegate exactly — B must disappear from the pool.
        delegate.update(ImmutableList.of(a, c));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(a, c);
    }

    @Test
    void strictMode_excludesBadEndpoints() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        final Endpoint a = Endpoint.of("a.com");
        final Endpoint b = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(a, b));

        endpointGroup.endpointContext(a).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(endpointGroup.endpoints()).containsExactly(b);
    }

    @Test
    void strictMode_capsAtMaxNumEndpoints() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .build();

        final Endpoint a = Endpoint.of("a.com");
        final Endpoint b = Endpoint.of("b.com");
        final Endpoint c = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(a, b, c));
        assertThat(endpointGroup.endpoints()).hasSize(2);
    }

    @Test
    void keepAlive_fillsFromCacheWhenDelegateIsInsufficient() {
        // maxNumEndpoints=2, age=10min. Delegate gives [a, b] then replaces b with c.
        // The pool was full, so the addListener gate skips the refresh; the cache holds b alive.
        // When a's circuit opens, the refresh triggers and the cache fills the gap with b (still
        // not expired) plus c from the delegate.
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofMinutes(10))
                                             .build();

        final Endpoint a = Endpoint.of("a.com");
        final Endpoint b = Endpoint.of("b.com");
        final Endpoint c = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(a, b));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(a, b);

        delegate.update(ImmutableList.of(a, c));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(a, b);

        endpointGroup.endpointContext(a).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(b, c);
    }

    @Test
    void keepAlive_capsAtMaxNumEndpoints() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofMinutes(10))
                                             .build();

        final Endpoint a = Endpoint.of("a.com");
        final Endpoint b = Endpoint.of("b.com");
        final Endpoint c = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(a, b, c));
        assertThat(endpointGroup.endpoints()).hasSize(2);
    }

    @Test
    void replacesEndpointWhenItsCircuitOpens() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));

        // Round-robin selection picks A, then B.
        assertThat(endpointGroup.selectNow(newCtx())).isEqualTo(endpointA);
        assertThat(endpointGroup.selectNow(newCtx())).isEqualTo(endpointB);

        // Open the circuit breaker for A.
        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);

        // The next selection should skip A.
        assertThat(endpointGroup.selectNow(newCtx())).isEqualTo(endpointB);
        assertThat(endpointGroup.selectNow(newCtx())).isEqualTo(endpointB);
        assertThat(endpointGroup.selectNow(newCtx())).isEqualTo(endpointB);
        assertThat(endpointGroup.endpointContext(endpointA)).isNull();
    }

    @Test
    void keepsExistingEndpointsWhenAllCircuitsAreClosed() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        // Keep-alive mode: cached endpoints survive delegate churn until they age out.
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofMinutes(10))
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        final Endpoint endpointC = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));

        assertThat(endpointGroup.endpoints()).containsExactly(endpointA, endpointB);

        // The pool is full and every circuit is closed: changes from the delegate should be ignored.
        delegate.update(ImmutableList.of(endpointA, endpointC));
        assertThat(endpointGroup.endpoints()).containsExactly(endpointA, endpointB);
    }

    @Test
    void refreshesEndpointsWhenMaxAgeElapses() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofSeconds(1))
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        final Endpoint endpointC = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB);

        delegate.update(ImmutableList.of(endpointA, endpointC));
        // Pool is full, no circuit open: changes should be ignored.
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB);

        // After maxEndpointAge elapses, the scheduler picks up the new endpoints.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointC));
    }

    @Test
    void refreshNeverExceedsMaxNumEndpointsWhenReusingOldOnes() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofSeconds(1))
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        final Endpoint endpointC = Endpoint.of("c.com");
        // The pool fills with the first two from the delegate; C is a leftover candidate.
        delegate.update(ImmutableList.of(endpointA, endpointB, endpointC));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB);

        // After maxEndpointAge elapses both A and B become old endpoints. The new-endpoint loop fills
        // one slot with C (the only non-old candidate), and the reuse loop must respect the remaining
        // slot count rather than repopulating both A and B (which would push the pool to size 3).
        // Wait for C to appear (proving refresh-after-expiration has run), then assert the cap holds.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(endpointGroup.endpoints()).contains(endpointC));
        assertThat(endpointGroup.endpoints()).hasSize(2);
    }

    @Test
    void replacesBadEndpointsAndAddsFreshOnes() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        // Keep-alive mode: a bad endpoint is replaced via the cache (b) plus a fresh candidate (c).
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .maxEndpointAge(Duration.ofMinutes(10))
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        final Endpoint endpointC = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB);

        // Delegate now reports A and C, but the pool is full so we keep A and B.
        delegate.update(ImmutableList.of(endpointA, endpointC));
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB);

        // Open A's circuit; A becomes a bad endpoint and C fills the gap.
        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointB, endpointC);
    }

    @Test
    void fallsBackToBadEndpointsWhenAllCircuitsAreOpen() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));

        // Open both circuits so the pool is empty and the bad set has both endpoints.
        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);
        endpointGroup.endpointContext(endpointB).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(endpointGroup.endpointContext(endpointA)).isNull();
        assertThat(endpointGroup.endpointContext(endpointB)).isNull();

        final ClientRequestContext ctx = newCtx();
        assertThat(endpointGroup.selectNow(ctx)).isNull();

        // The async select() randomly picks a bad endpoint as a fallback. Both must be reachable.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            final Endpoint picked = endpointGroup.select(ctx, ctx.eventLoop()).join();
            return endpointA.equals(picked);
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            final Endpoint picked = endpointGroup.select(ctx, ctx.eventLoop()).join();
            return endpointB.equals(picked);
        });

        // Once the delegate reports a healthy endpoint, that takes precedence over the bad ones.
        // The inner round-robin selector picks up the new endpoint via an asynchronous listener
        // notification, so wait until it is observable.
        final Endpoint endpointC = Endpoint.of("c.com");
        delegate.update(ImmutableList.of(endpointC));
        await().untilAsserted(() -> assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpointC));
    }

    @Test
    void failFastOnAllCircuitOpenSkipsBadEndpointFallback() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .failFastOnAllCircuitOpen(true)
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));
        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);
        endpointGroup.endpointContext(endpointB).circuitBreaker().enterState(CircuitState.OPEN);

        final ClientRequestContext ctx = newCtx();
        // Without bad-endpoint fallback, the async selector waits for fresh endpoints; the future
        // should not complete with a bad endpoint right away.
        assertThat(endpointGroup.select(ctx, ctx.eventLoop())).isNotDone();
    }

    @Test
    void expiredBadEndpointsBecomeAvailableAgain() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final long circuitOpenWindowMillis = 1000;
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .circuitOpenWindowMillis(circuitOpenWindowMillis)
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));

        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(endpointGroup.endpoints()).containsExactly(endpointB);

        // After the bad-endpoint expiration window, A should be re-added.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(endpointA, endpointB));
    }

    @Test
    void exportsHealthyAndUnhealthyGauges() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(2)
                                             .meterRegistry(new MeterIdPrefix("my.test"), registry)
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        delegate.update(ImmutableList.of(endpointA, endpointB));

        final Gauge healthy = registry.find("my.test.endpoints.count")
                                      .tag("state", "healthy")
                                      .gauge();
        final Gauge unhealthy = registry.find("my.test.endpoints.count")
                                        .tag("state", "unhealthy")
                                        .gauge();
        assertThat(healthy).isNotNull();
        assertThat(unhealthy).isNotNull();
        assertThat(healthy.value()).isEqualTo(2.0);
        assertThat(unhealthy.value()).isEqualTo(0.0);

        endpointGroup.endpointContext(endpointA).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(healthy.value()).isEqualTo(1.0);
        assertThat(unhealthy.value()).isEqualTo(1.0);

        endpointGroup.endpointContext(endpointB).circuitBreaker().enterState(CircuitState.OPEN);
        assertThat(healthy.value()).isEqualTo(0.0);
        assertThat(unhealthy.value()).isEqualTo(2.0);
    }

    @Test
    void retrySkipsAlreadyAttemptedEndpoints() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .maxNumEndpoints(10)
                                             .maxEndpointAge(Duration.ofSeconds(1))
                                             .build();

        final ImmutableList.Builder<Endpoint> candidatesBuilder = ImmutableList.builder();
        for (int i = 1; i <= 20; i++) {
            candidatesBuilder.add(Endpoint.of(String.format("%02d.com", i)));
        }
        final List<Endpoint> candidates = candidatesBuilder.build();
        delegate.update(candidates);
        final List<Endpoint> endpoints = endpointGroup.endpoints();
        assertThat(endpoints).containsExactlyInAnyOrderElementsOf(candidates.subList(0, 10));

        final DefaultClientRequestContext parent = (DefaultClientRequestContext)
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();

        // Attach three child contexts representing previous selections of endpoints[0..2].
        addChild(parent, endpoints.get(0));
        addChild(parent, endpoints.get(1));
        addChild(parent, endpoints.get(2));

        final ClientRequestContext retry = parent.newDerivedContext(
                RequestId.random(), HttpRequest.of(HttpMethod.GET, "/"), null, null);
        parent.logBuilder().addChild(retry.log());

        // The selector should skip the three already-attempted endpoints and pick endpoints[3].
        assertThat(endpointGroup.selectNow(retry)).isEqualTo(endpoints.get(3));
    }

    @Test
    void builderRejectsInvalidArguments() {
        assertThatThrownBy(() -> OutlierDetectingEndpointGroup.builder(null))
                .isInstanceOf(NullPointerException.class);

        final OutlierDetectingEndpointGroupBuilder builder =
                OutlierDetectingEndpointGroup.builder(new SettableEndpointGroup());

        assertThatThrownBy(() -> builder.maxNumEndpoints(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxEndpointAgeMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxEndpointAge(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.namePrefix(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.failureRateThreshold(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.failureRateThreshold(1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.minimumRequestThreshold(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.trialRequestIntervalMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.circuitOpenWindowMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.counterSlidingWindowMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.counterUpdateIntervalMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.circuitBreakerRule(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.meterRegistry(null, new SimpleMeterRegistry()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.meterRegistry(new MeterIdPrefix("x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void closeDelegatesToWrappedGroup() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        endpointGroup.close();
        assertThat(delegate.closed).isTrue();
    }

    @Test
    void closeAsyncDelegatesToWrappedGroup() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        endpointGroup.closeAsync().join();
        assertThat(delegate.closed).isTrue();
    }

    @Test
    void selectionTimeoutMillisDelegatesToWrappedGroup() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();
        assertThat(endpointGroup.selectionTimeoutMillis())
                .isEqualTo(delegate.selectionTimeoutMillis());
    }

    @Test
    void selectionStrategyReturnsCustomStrategy() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();
        // The strategy is the OutlierDetectingEndpointGroup's circuit-breaker-aware one, not the
        // delegate's plain round-robin.
        assertThat(endpointGroup.selectionStrategy())
                .isNotEqualTo(delegate.selectionStrategy());
    }

    @Test
    void namePrefixIsAppliedToCircuitBreakerNames() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .namePrefix("custom-prefix")
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        delegate.update(ImmutableList.of(endpointA));

        final String circuitBreakerName =
                endpointGroup.endpointContext(endpointA).circuitBreaker().name();
        assertThat(circuitBreakerName).startsWith("custom-prefix-");
        assertThat(circuitBreakerName).endsWith(":" + endpointA);
    }

    @Test
    void removeListenerStopsNotifications() throws InterruptedException {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        final AtomicInteger callCount = new AtomicInteger();
        final Consumer<List<Endpoint>> listener = endpoints -> callCount.incrementAndGet();
        endpointGroup.addListener(listener);
        endpointGroup.removeListener(listener);

        delegate.update(ImmutableList.of(Endpoint.of("a.com")));
        // Allow the synchronous and any async refresh tasks to fire before asserting.
        Thread.sleep(500);
        assertThat(callCount.get()).isZero();
    }

    @Test
    void addListenerWithNotifyLatestEndpointsRepliesCurrentState() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        delegate.update(ImmutableList.of(Endpoint.of("a.com")));
        // Now subscribe with notifyLatestEndpoints=true; the listener should see the current state
        // immediately.
        final AtomicReference<List<Endpoint>> captor = new AtomicReference<>();
        endpointGroup.addListener(captor::set, true);
        assertThat(captor.get()).containsExactly(Endpoint.of("a.com"));
    }

    @Test
    void selectNowReturnsNullWhenGroupIsEmpty() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate).build();

        // No endpoints have been set yet.
        assertThat(endpointGroup.selectNow(newCtx())).isNull();
    }

    @Test
    void perEndpointCircuitBreakerUsesConfiguredDurations() {
        final SettableEndpointGroup delegate = new SettableEndpointGroup();
        final OutlierDetectingEndpointGroup endpointGroup =
                OutlierDetectingEndpointGroup.builder(delegate)
                                             .failureRateThreshold(0.42)
                                             .minimumRequestThreshold(7)
                                             .trialRequestIntervalMillis(123)
                                             .circuitOpenWindowMillis(456)
                                             .counterSlidingWindowMillis(789)
                                             .counterUpdateIntervalMillis(50)
                                             .build();

        final Endpoint endpointA = Endpoint.of("a.com");
        delegate.update(ImmutableList.of(endpointA));

        // The circuit breaker name proves the per-endpoint instance is wired to the configured prefix
        // and counter, and the bad-endpoint expiration is sourced from circuitOpenWindow.
        final String name = endpointGroup.endpointContext(endpointA).circuitBreaker().name();
        assertThat(name).startsWith("outlier-detecting-");
        assertThat(name).endsWith(":" + endpointA);
    }

    private static ClientRequestContext newCtx() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    private static void addChild(DefaultClientRequestContext parent, Endpoint endpoint) {
        final ClientRequestContext child = parent.newDerivedContext(
                RequestId.random(), HttpRequest.of(HttpMethod.GET, "/"), null, endpoint);
        parent.logBuilder().addChild(child.log());
    }

    private static final class SettableEndpointGroup extends DynamicEndpointGroup {

        volatile boolean closed;

        SettableEndpointGroup() {
            super(EndpointSelectionStrategy.roundRobin());
        }

        void update(Iterable<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }

        @Override
        protected void doCloseAsync(CompletableFuture<?> future) {
            closed = true;
            super.doCloseAsync(future);
        }
    }
}
