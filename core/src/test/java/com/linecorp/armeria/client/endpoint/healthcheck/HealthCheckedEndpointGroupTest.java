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

import static com.linecorp.armeria.client.endpoint.healthcheck.AbstractHealthCheckedEndpointGroupBuilder.DEFAULT_HEALTH_CHECK_RETRY_BACKOFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;

import io.netty.channel.EventLoopGroup;

class HealthCheckedEndpointGroupTest {

    private static final double UNHEALTHY = 0;
    private static final double HEALTHY = 1;

    @Test
    void delegateUpdateCandidatesWhileCreatingHealthCheckedEndpointGroup() {
        final MockEndpointGroup delegate = new MockEndpointGroup();
        final CompletableFuture<List<Endpoint>> future = delegate.whenReady();
        future.complete(ImmutableList.of(Endpoint.of("127.0.0.1", 8080), Endpoint.of("127.0.0.1", 8081)));

        final CountDownLatch latch = new CountDownLatch(1);

        // Schedule the task which updates the endpoint one second later to ensure that the change is happening
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
                    // Call updateHealth *after* the endpoint is changed so that
                    // snapshot.forEach(ctx -> ctx.initialCheckFuture.join()); performs the next action.
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            latch.await();
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                        ctx.updateHealth(1);
                    }).start();
                    return AsyncCloseableSupport.of();
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
                    return AsyncCloseableSupport.of();
                };
            }
        }.build()) {
            assertThat(group.whenReady().get(10, TimeUnit.SECONDS)).isEmpty();
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
                    return AsyncCloseableSupport.of();
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
            assertThatThrownBy(() -> ctx.executor().execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("destroyed");
        }
    }

    @Test
    void updatesSelectedCandidatesNoStackOverflowEvenUpdatesOnEqualThread() {
        final AtomicReference<HealthCheckerContext> firstSelectedCandidates = new AtomicReference<>();
        final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkFactory = ctx -> {
            if (firstSelectedCandidates.get() == null) {
                firstSelectedCandidates.set(ctx);
            }

            ctx.updateHealth(HEALTHY);
            return AsyncCloseableSupport.of();
        };

        final Endpoint candidate1 = Endpoint.of("candidate1");
        final Endpoint candidate2 = Endpoint.of("candidate2");

        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(candidate1, candidate2);

        try (HealthCheckedEndpointGroup group =
                     new HealthCheckedEndpointGroup(delegate, SessionProtocol.HTTP, 80,
                                                    DEFAULT_HEALTH_CHECK_RETRY_BACKOFF,
                                                    ClientOptions.of(), checkFactory,
                                                    new InfinityUpdateHealthCheckStrategy())) {

            assertThat(group.healthyEndpoints).containsOnly(candidate1, candidate2);

            firstSelectedCandidates.get().updateHealth(UNHEALTHY);
            assertThat(group.healthyEndpoints).containsOnly(candidate2);
        }
    }

    @Test
    void shouldCallRefreshWhenEndpointWeightIsChanged() throws InterruptedException {
        final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkFactory = ctx -> {
            ctx.updateHealth(HEALTHY);
            return AsyncCloseableSupport.of();
        };

        final Endpoint candidate1 = Endpoint.of("candidate1");
        final Endpoint candidate2 = Endpoint.of("candidate2");

        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(candidate1, candidate2);

        try (HealthCheckedEndpointGroup group =
                     new HealthCheckedEndpointGroup(delegate, SessionProtocol.HTTP, 80,
                                                    DEFAULT_HEALTH_CHECK_RETRY_BACKOFF,
                                                    ClientOptions.of(), checkFactory,
                                                    new InfinityUpdateHealthCheckStrategy())) {

            assertThat(group.endpoints()).usingElementComparator(new EndpointComparator())
                                         .containsOnly(candidate1, candidate2);
            delegate.set(candidate1.withWeight(150), candidate2);
            assertThat(group.endpoints()).usingElementComparator(new EndpointComparator())
                                         .containsOnly(candidate1.withWeight(150), candidate2);
        }
    }

    @Test
    void makeOneContextForSameEndpoints() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger();
        final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkFactory = ctx -> {
            counter.incrementAndGet();
            ctx.updateHealth(HEALTHY);
            return AsyncCloseableSupport.of();
        };

        final Endpoint candidate1 = Endpoint.of("candidate1");
        final Endpoint candidate2 = Endpoint.of("candidate2");

        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(candidate1, candidate2, candidate2);

        try (HealthCheckedEndpointGroup unused =
                     new HealthCheckedEndpointGroup(delegate, SessionProtocol.HTTP, 80,
                                                    DEFAULT_HEALTH_CHECK_RETRY_BACKOFF,
                                                    ClientOptions.of(), checkFactory,
                                                    new InfinityUpdateHealthCheckStrategy())) {
            assertThat(counter.get()).isEqualTo(2);
        }
    }

    @Test
    void closesWhenClientFactoryCloses() {
        final ClientFactory factory = ClientFactory.builder().build();
        final EndpointGroup delegate = Endpoint.of("foo");
        final AsyncCloseableSupport checkerCloseable = AsyncCloseableSupport.of();
        final AtomicInteger newCheckerCount = new AtomicInteger();
        final HealthCheckedEndpointGroup group = new AbstractHealthCheckedEndpointGroupBuilder(delegate) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    ctx.updateHealth(1);
                    newCheckerCount.incrementAndGet();
                    return checkerCloseable;
                };
            }
        }.clientFactory(factory).build();

        // When the ClientFactory is closed, the health checkers must be closed as well.
        factory.close();
        await().untilAsserted(() -> {
            assertThat(group.isClosed()).isTrue();
            assertThat(group.isClosing()).isTrue();
            assertThat(checkerCloseable.isClosing()).isTrue();
            assertThat(checkerCloseable.isClosed()).isTrue();
        });

        // No more than one checker must be created.
        assertThat(newCheckerCount).hasValue(1);
    }

    private static final class MockEndpointGroup extends DynamicEndpointGroup {
        void set(Endpoint... endpoints) {
            setEndpoints(ImmutableList.copyOf(endpoints));
        }
    }

    private static final class InfinityUpdateHealthCheckStrategy implements HealthCheckStrategy {
        private List<Endpoint> candidates;
        private List<Endpoint> selectedCandidates;

        InfinityUpdateHealthCheckStrategy() {
            candidates = new ArrayList<>();
            selectedCandidates = ImmutableList.copyOf(candidates);
        }

        @Override
        public void updateCandidates(List<Endpoint> candidates) {
            this.candidates = candidates;
            selectedCandidates = ImmutableList.copyOf(candidates);
        }

        @Override
        public List<Endpoint> getSelectedEndpoints() {
            return selectedCandidates;
        }

        @Override
        public boolean updateHealth(Endpoint endpoint, double health) {
            return true;
        }
    }

    /**
     * A Comparator which includes the weight of an endpoint to compare.
     */
    private static class EndpointComparator implements Comparator<Endpoint>, Serializable {
        private static final long serialVersionUID = 6866869171110624149L;

        @Override
        public int compare(Endpoint o1, Endpoint o2) {
            if (o1.equals(o2) && o1.weight() == o2.weight()) {
                return 0;
            }
            return -1;
        }
    }
}
