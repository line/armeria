/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.AsyncCloseable;

import io.netty.channel.EventLoopGroup;

class HealthCheckedEndpointGroupTest {
    private static final double UNHEALTHY = 0;
    private static final double HEALTHY = 1;

    @Test
    void delegateUpdateCandidatesWhileCreatingHealthCheckedEndpointGroup() {
        final MockEndpointGroup delegate = new MockEndpointGroup();
        final CompletableFuture<List<Endpoint>> future = delegate.initialEndpointsFuture();
        future.complete(ImmutableList.of(Endpoint.of("127.0.0.1", 8080), Endpoint.of("127.0.0.1", 8081)));

        final CountDownLatch latch = new CountDownLatch(1);

        // Schedule the task which update the endpoint one second later to ensure that the change is happening
        // while creating the HealthCheckedEndpointGroup.
        final EventLoopGroup executors = CommonPools.workerGroup();
        executors.schedule(
                () -> {
                    delegate.set(Endpoint.of("127.0.0.1", 8082));
                    latch.countDown();
                }, 1, TimeUnit.SECONDS);

        new AbstractHealthCheckedEndpointGroupBuilder(delegate) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return (Function<HealthCheckerContext, AsyncCloseable>) ctx -> {
                    // Call updateHealth after the endpoint is changed so that
                    // snapshot.forEach(ctx -> ctx.initialCheckFuture.join()); performs the next action.
                    executors.schedule(() -> {
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                        ctx.updateHealth(1);
                    }, 2, TimeUnit.SECONDS);
                    return CompletableFuture::new;
                };
            }
        }.build();
    }

    @Test
    void startsUnhealthyAwaitsForEmptyEndpoints() throws Exception {
        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(Endpoint.of("foo"));
        final AtomicReference<HealthCheckerContext> ctxCapture = new AtomicReference<>();

        try (HealthCheckedEndpointGroup group = new AbstractHealthCheckedEndpointGroupBuilder(delegate) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    ctxCapture.set(ctx);
                    ctx.updateHealth(0);
                    return () -> CompletableFuture.completedFuture(null);
                };
            }
        }.build()) {
            assertThat(group.awaitInitialEndpoints(10, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void disappearedEndpoint() {
        // Start with an endpoint group that has healthy 'foo'.
        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(Endpoint.of("foo"));
        final AtomicReference<HealthCheckerContext> ctxCapture = new AtomicReference<>();

        try (HealthCheckedEndpointGroup group = new AbstractHealthCheckedEndpointGroupBuilder(delegate) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    ctxCapture.set(ctx);
                    ctx.updateHealth(1);
                    return () -> CompletableFuture.completedFuture(null);
                };
            }
        }.build()) {

            // Check the initial state.
            final HealthCheckerContext ctx = ctxCapture.get();
            assertThat(ctx).isNotNull();
            assertThat(ctx.endpoint()).isEqualTo(Endpoint.of("foo"));

            // 'foo' did not disappear yet, so the task must be accepted and run.
            final AtomicBoolean taskRun = new AtomicBoolean();
            ctx.executor().execute(() -> taskRun.set(true));
            await().untilAsserted(() -> assertThat(taskRun).isTrue());

            // Make 'foo' disappear.
            delegate.set();

            // 'foo' should not be healthy anymore.
            assertThat(group.endpoints()).isEmpty();

            // 'foo' should not be healthy even if `ctx.updateHealth()` was called.
            ctx.updateHealth(1);
            assertThat(group.endpoints()).isEmpty();
            assertThat(group.healthyEndpoints).isEmpty();

            // An attempt to schedule a new task for a disappeared endpoint must fail.
            assertThatThrownBy(() -> ctx.executor().execute(() -> {
            }))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("destroyed");
        }
    }

    @Test
    void updatesSelectedCandidatesByHealthCheckStrategyOnEqualThread() {
        final Endpoint candidate1 = Endpoint.of("candidate1");
        final Endpoint candidate2 = Endpoint.of("candidate2");
        final Endpoint candidate3 = Endpoint.of("candidate3");

        final Endpoint candidate4 = Endpoint.of("candidate4");
        final Endpoint candidate5 = Endpoint.of("candidate5");
        final Endpoint candidate6 = Endpoint.of("candidate6");

        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(candidate1, candidate2, candidate3);

        final List<HealthCheckerContext> ctxCaptures = new ArrayList<>();

        try (HealthCheckedEndpointGroup group = new AbstractHealthCheckedEndpointGroupBuilder(delegate) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    ctxCaptures.add(ctx);
                    ctx.updateHealth(HEALTHY);
                    return () -> CompletableFuture.completedFuture(null);
                };
            }
        }.healthCheckStrategy(new MockHealthCheckStrategy())
         .build()) {
            final Set<Endpoint> initialHealthyEndpoint = group.healthyEndpoints;
            // Because of it's called by an equal thread,
            // there is no infinity loop even health check strategy always returns true.
            assertThat(initialHealthyEndpoint).containsOnly(candidate1, candidate2, candidate3);

            // update candidate1, candidate3 to unhealthy.
            ctxCaptures.get(0).updateHealth(UNHEALTHY);
            ctxCaptures.get(2).updateHealth(UNHEALTHY);

            // candidate2 is only healthy.
            final Set<Endpoint> afterHealthyEndpoint = group.healthyEndpoints;
            assertThat(afterHealthyEndpoint).containsOnly(candidate2);

            // update delegate's candidates
            delegate.set(candidate4, candidate5, candidate6);
            final Set<Endpoint> newInitialHealthyEndpoint = group.healthyEndpoints;
            assertThat(newInitialHealthyEndpoint).containsOnly(candidate4, candidate5, candidate6);
        }
    }

    private static final class MockEndpointGroup extends DynamicEndpointGroup {
        void set(Endpoint... endpoints) {
            setEndpoints(ImmutableList.copyOf(endpoints));
        }
    }

    private static final class MockHealthCheckStrategy implements HealthCheckStrategy {
        private List<Endpoint> candidates;
        private List<Endpoint> selectedCandidates;

        MockHealthCheckStrategy() {
            candidates = new ArrayList<>();
            selectedCandidates = ImmutableList.copyOf(candidates);
        }

        @Override
        public void updateCandidates(List<Endpoint> candidates) {
            this.candidates = candidates;
            selectedCandidates = ImmutableList.copyOf(candidates);
        }

        @Override
        public List<Endpoint> getCandidates() {
            return selectedCandidates;
        }

        @Override
        public boolean updateHealth(Endpoint endpoint, double health) {
            if (health <= 0) {
                selectedCandidates = ImmutableList.copyOf(selectedCandidates.stream()
                                                        .filter(candidate -> !candidate.equals(endpoint))
                                                        .collect(Collectors.toList()));
            }

            return true;
        }
    }
}
