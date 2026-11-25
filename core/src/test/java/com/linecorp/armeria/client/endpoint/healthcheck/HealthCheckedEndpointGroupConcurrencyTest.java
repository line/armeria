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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;

class HealthCheckedEndpointGroupConcurrencyTest {

    private static final Endpoint A = Endpoint.of("a");
    private static final Endpoint B = Endpoint.of("b");
    private static final Endpoint C = Endpoint.of("c");

    @Test
    void defaultExecutorIsSharedAcrossGroups() throws Exception {
        final TestBuilder firstBuilder = new TestBuilder(A);
        final TestBuilder secondBuilder = new TestBuilder(B);
        try (HealthCheckedEndpointGroup first = firstBuilder.build();
             HealthCheckedEndpointGroup second = secondBuilder.build()) {
            first.whenReady().get(10, TimeUnit.SECONDS);
            second.whenReady().get(10, TimeUnit.SECONDS);
            final CompletableFuture<Thread> firstThread = new CompletableFuture<>();
            final CompletableFuture<Thread> secondThread = new CompletableFuture<>();
            first.addListener(unused -> firstThread.complete(Thread.currentThread()));
            second.addListener(unused -> secondThread.complete(Thread.currentThread()));

            unhealthy(firstBuilder.contexts.get(A));
            unhealthy(secondBuilder.contexts.get(B));
            final Thread executorThread = firstThread.get(10, TimeUnit.SECONDS);
            assertThat(executorThread).isSameAs(secondThread.get(10, TimeUnit.SECONDS))
                                      .isNotSameAs(Thread.currentThread())
                                      .isInstanceOf(FastThreadLocalThread.class);
            assertThat(((FastThreadLocalThread) executorThread).permitBlockingCalls()).isTrue();
        }
    }

    @Test
    void failsReadinessAndClosesStartedCheckerWhenFactoryThrows() throws Exception {
        final AssertionError failure = new AssertionError("checker creation failed");
        final AtomicInteger started = new AtomicInteger();
        final CompletableFuture<Void> checkerClosed = new CompletableFuture<>();
        final TestBuilder builder = new TestBuilder(EndpointGroup.of(A, B)) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    if (started.incrementAndGet() == 2) {
                        throw failure;
                    }
                    ctx.updateHealth(1, null, null, null);
                    return AsyncCloseableSupport.of(future -> {
                        checkerClosed.complete(null);
                        future.complete(null);
                    });
                };
            }
        };
        final HealthCheckedEndpointGroup group = builder.build();
        try {
            assertThatThrownBy(() -> group.whenReady().get(10, TimeUnit.SECONDS)).hasCauseReference(failure);
            group.whenClosed().get(10, TimeUnit.SECONDS);
            assertThat(checkerClosed).isCompleted();
        } finally {
            group.closeAsync();
        }
    }

    @Test
    void keepsInitialSelectionTimeoutWhileInitializationIsQueued() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        final HealthCheckedEndpointGroup group = new TestBuilder(A).executor(executor)
                                                                  .selectionTimeoutMillis(10000, 10)
                                                                  .build();
        try {
            assertThat(group.whenReady()).isNotDone();
            assertThat(group.selectionTimeoutMillis()).isEqualTo(10000 + A.selectionTimeoutMillis());
            release.countDown();
            assertThat(group.whenReady().get(10, TimeUnit.SECONDS)).containsExactly(A);
            assertThat(group.selectionTimeoutMillis()).isEqualTo(10 + A.selectionTimeoutMillis());
        } finally {
            release.countDown();
            group.closeAsync().get(10, TimeUnit.SECONDS);
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void calculatesEndpointsOnExecutorUsingLatestHealth() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final TestBuilder builder = new TestBuilder(EndpointGroup.of(A, B, C));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (HealthCheckedEndpointGroup group = builder.executor(executor).build()) {
            assertThat(group.whenReady().get(10, TimeUnit.SECONDS)).containsExactly(A, B, C);
            final List<List<Endpoint>> updates = new CopyOnWriteArrayList<>();
            group.addListener(updates::add);
            final Future<?> blocked = executor.submit(() -> {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return null;
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            unhealthy(builder.contexts.get(A));
            unhealthy(builder.contexts.get(B));
            assertThat(group.endpoints()).containsExactly(A, B, C);
            release.countDown();
            blocked.get(10, TimeUnit.SECONDS);
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);

            assertThat(group.endpoints()).containsExactly(C);
            assertThat(updates).containsExactly(ImmutableList.of(C));
            unhealthy(builder.contexts.get(A));
            unhealthy(builder.contexts.get(B));
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertThat(group.endpoints()).containsExactly(C);
            assertThat(updates).hasSize(1);
        } finally {
            release.countDown();
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void serializesPublicationAndNotifiesOutsideGroupLock() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final TestBuilder builder = new TestBuilder(EndpointGroup.of(A, B, C));
        final CountDownLatch listenerEntered = new CountDownLatch(1);
        final CountDownLatch releaseListener = new CountDownLatch(1);
        try (HealthCheckedEndpointGroup group = builder.executor(executor).build()) {
            group.whenReady().get(10, TimeUnit.SECONDS);
            final List<List<Endpoint>> updates = new CopyOnWriteArrayList<>();
            group.addListener(endpoints -> {
                updates.add(endpoints);
                if (endpoints.equals(ImmutableList.of(B, C))) {
                    listenerEntered.countDown();
                    try {
                        assertThat(releaseListener.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            });

            unhealthy(builder.contexts.get(A));
            assertThat(listenerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            unhealthy(builder.contexts.get(B));
            assertThat(group.endpoints()).containsExactly(B, C);
            assertThat(CompletableFuture.supplyAsync(group::allHealthyEndpoints)
                                        .get(10, TimeUnit.SECONDS)).containsExactly(C);
            releaseListener.countDown();
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);

            assertThat(group.endpoints()).containsExactly(C);
            assertThat(updates).containsExactly(ImmutableList.of(B, C), ImmutableList.of(C));
        } finally {
            releaseListener.countDown();
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void listenerUpdatesAreQueuedInsteadOfReentering() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final TestBuilder builder = new TestBuilder(EndpointGroup.of(A, B, C));
        try (HealthCheckedEndpointGroup group = builder.executor(executor).build()) {
            group.whenReady().get(10, TimeUnit.SECONDS);
            final AtomicInteger depth = new AtomicInteger();
            final AtomicInteger maxDepth = new AtomicInteger();
            final AtomicBoolean unchangedDuringListener = new AtomicBoolean();
            final CompletableFuture<Void> finished = new CompletableFuture<>();
            group.addListener(endpoints -> {
                final int currentDepth = depth.incrementAndGet();
                maxDepth.accumulateAndGet(currentDepth, Math::max);
                if (endpoints.equals(ImmutableList.of(B, C))) {
                    unhealthy(builder.contexts.get(B));
                    unchangedDuringListener.set(group.endpoints().equals(ImmutableList.of(B, C)));
                } else if (endpoints.equals(ImmutableList.of(C))) {
                    finished.complete(null);
                }
                depth.decrementAndGet();
            });

            unhealthy(builder.contexts.get(A));
            finished.get(10, TimeUnit.SECONDS);
            assertThat(group.endpoints()).containsExactly(C);
            assertThat(unchangedDuringListener).isTrue();
            assertThat(maxDepth).hasValue(1);
        } finally {
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void listenerCanCloseGroupAsynchronously() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final TestBuilder builder = new TestBuilder(EndpointGroup.of(A, B));
        final HealthCheckedEndpointGroup group = builder.executor(executor).build();
        try {
            group.whenReady().get(10, TimeUnit.SECONDS);
            final CompletableFuture<Void> closed = new CompletableFuture<>();
            group.addListener(unused -> group.closeAsync().whenComplete((result, cause) -> {
                if (cause != null) {
                    closed.completeExceptionally(cause);
                } else {
                    closed.complete(null);
                }
            }));
            unhealthy(builder.contexts.get(A));
            closed.get(10, TimeUnit.SECONDS);
            assertThat(group.isClosed()).isTrue();
        } finally {
            group.closeAsync();
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void listenerCanCloseNestedGroupsAfterCheckerClosesAsynchronously() throws Exception {
        final EventExecutor executor = newEventExecutor();
        final CompletableFuture<Void> finishCheckerClose = new CompletableFuture<>();
        final CountDownLatch checkerCloseStarted = new CountDownLatch(1);
        final HealthCheckedEndpointGroup inner = new TestBuilder(A).executor(executor).build();
        inner.whenReady().get(10, TimeUnit.SECONDS);
        final TestBuilder outerBuilder = new TestBuilder(inner) {
            @Override
            protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
                return ctx -> {
                    contexts.put(ctx.originalEndpoint(), ctx);
                    ctx.updateHealth(1, null, null, null);
                    return AsyncCloseableSupport.of(future -> {
                        checkerCloseStarted.countDown();
                        finishCheckerClose.thenRun(() -> future.complete(null));
                    });
                };
            }
        };
        final HealthCheckedEndpointGroup outer = outerBuilder.executor(executor).build();
        try {
            outer.whenReady().get(10, TimeUnit.SECONDS);
            final CompletableFuture<Void> closed = new CompletableFuture<>();
            outer.addListener(unused -> outer.closeAsync().whenComplete((result, cause) -> {
                if (cause != null) {
                    closed.completeExceptionally(cause);
                } else {
                    closed.complete(null);
                }
            }));
            unhealthy(outerBuilder.contexts.get(A));
            assertThat(checkerCloseStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(outer.isClosed()).isFalse();
            assertThat(inner.isClosing()).isFalse();
            finishCheckerClose.complete(null);
            closed.get(10, TimeUnit.SECONDS);
            assertThat(outer.isClosed()).isTrue();
            assertThat(inner.isClosed()).isTrue();
        } finally {
            finishCheckerClose.complete(null);
            outer.closeAsync().get(10, TimeUnit.SECONDS);
            inner.closeAsync().get(10, TimeUnit.SECONDS);
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void closingGroupDoesNotCloseUserExecutor() throws Exception {
        final EventExecutor executor = newEventExecutor();
        try {
            final HealthCheckedEndpointGroup group = new TestBuilder(A).executor(executor).build();
            group.whenReady().get(10, TimeUnit.SECONDS);
            group.closeAsync().get(10, TimeUnit.SECONDS);
            assertThat(executor.submit(() -> true).get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }

    private static EventExecutor newEventExecutor() {
        return new DefaultEventExecutor(ThreadFactories.newThreadFactory("health-check-test", true));
    }

    private static void unhealthy(HealthCheckerContext context) {
        context.updateHealth(0, null, null, new AnticipatedException());
    }

    private static class TestBuilder extends AbstractHealthCheckedEndpointGroupBuilder<TestBuilder> {

        final Map<Endpoint, HealthCheckerContext> contexts = new ConcurrentHashMap<>();

        TestBuilder(EndpointGroup delegate) {
            super(delegate);
        }

        @Override
        protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
            return ctx -> {
                contexts.put(ctx.originalEndpoint(), ctx);
                ctx.updateHealth(1, null, null, null);
                return AsyncCloseableSupport.of();
            };
        }
    }
}
