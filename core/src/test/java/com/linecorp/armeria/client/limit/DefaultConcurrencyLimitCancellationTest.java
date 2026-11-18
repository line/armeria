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

package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.RequestCancellation;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class DefaultConcurrencyLimitCancellationTest {

    private final ClientRequestContext normalCtx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @ParameterizedTest
    @ValueSource(longs = { 0, 60000 })
    void cancellationReleasesPendingSlotAndCancelsTimeout(long timeoutMillis) throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(false);
        final DefaultConcurrencyLimit limit =
                new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, timeoutMillis);
        try (SafeCloseable held = limit.acquire(normalCtx).join()) {
            final RequestCancellation cancellation = new RequestCancellation();
            final CompletableFuture<SafeCloseable> pending = limit.acquire(newContext(eventLoop, cancellation));
            assertThat(pending).isNotDone();
            final Throwable cause = new AnticipatedException();
            cancellation.cancel(cause);
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCause(cause);
            if (timeoutMillis != 0) {
                assertThat(eventLoop.scheduled.get(5, TimeUnit.SECONDS).isCancelled()).isTrue();
            }
            final CompletableFuture<SafeCloseable> replacement = queueSingleReplacement(limit);
            held.close();
            replacement.get(5, TimeUnit.SECONDS).close();
            assertThat(limit.acquiredPermits()).isZero();
            assertThat(limit.availablePermits()).isEqualTo(1);
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void cancellationBeforePermitHandoffReleasesReservedPermit() throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(false);
        final CountDownLatch release = new CountDownLatch(1);
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, 60000);
        try (SafeCloseable held = limit.acquire(normalCtx).join()) {
            final CompletableFuture<Void> blocked = new CompletableFuture<>();
            eventLoop.execute(() -> {
                blocked.complete(null);
                awaitRelease(release);
            });
            blocked.get(5, TimeUnit.SECONDS);
            final RequestCancellation cancellation = new RequestCancellation();
            final CompletableFuture<SafeCloseable> pending = limit.acquire(newContext(eventLoop, cancellation));
            held.close();
            assertThat(pending).isNotDone();
            assertThat(limit.acquiredPermits()).isEqualTo(1);
            final Throwable cause = new AnticipatedException();
            cancellation.cancel(cause);
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCause(cause);
            assertThat(eventLoop.scheduled.get(5, TimeUnit.SECONDS).isCancelled()).isTrue();
            assertThat(limit.acquiredPermits()).isZero();
            limit.acquire(normalCtx).get(5, TimeUnit.SECONDS).close();
            release.countDown();
            eventLoop.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(limit.acquiredPermits()).isZero();
        } finally {
            release.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void grantWinsAgainstAlreadyRunningTimeout() throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(true);
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, 60000);
        try (SafeCloseable held = limit.acquire(normalCtx).join()) {
            final RequestCancellation cancellation = new RequestCancellation();
            final ClientRequestContext ctx = newContext(eventLoop, cancellation);
            final CompletableFuture<SafeCloseable> pending = limit.acquire(ctx);
            final CompletableFuture<RequestContext> callbackContext = new CompletableFuture<>();
            pending.thenRun(() -> callbackContext.complete(RequestContext.currentOrNull()));
            eventLoop.timeoutStarted.get(5, TimeUnit.SECONDS);
            held.close();
            assertThat(pending).isNotDone();
            eventLoop.releaseTimeout.countDown();
            final CompletableFuture<SafeCloseable> replacement;
            try (SafeCloseable ignored = pending.get(5, TimeUnit.SECONDS)) {
                assertThat(callbackContext.get(5, TimeUnit.SECONDS)).isSameAs(ctx);
                cancellation.cancel(new AnticipatedException());
                assertThat(limit.acquiredPermits()).isEqualTo(1);
                replacement = queueSingleReplacement(limit);
            }
            replacement.get(5, TimeUnit.SECONDS).close();
            assertThat(limit.acquiredPermits()).isZero();
        } finally {
            eventLoop.releaseTimeout.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void cancellationWinsAgainstAlreadyRunningTimeout() throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(true);
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, 60000);
        try (SafeCloseable held = limit.acquire(normalCtx).join()) {
            final RequestCancellation cancellation = new RequestCancellation();
            final CompletableFuture<SafeCloseable> pending = limit.acquire(newContext(eventLoop, cancellation));
            eventLoop.timeoutStarted.get(5, TimeUnit.SECONDS);
            final Throwable cause = new AnticipatedException();
            cancellation.cancel(cause);
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCause(cause);
            final CompletableFuture<SafeCloseable> replacement = queueSingleReplacement(limit);
            eventLoop.releaseTimeout.countDown();
            eventLoop.submit(() -> {}).get(5, TimeUnit.SECONDS);
            held.close();
            replacement.get(5, TimeUnit.SECONDS).close();
            assertThat(limit.acquiredPermits()).isZero();
        } finally {
            eventLoop.releaseTimeout.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void timeoutWinsBeforeGrantAndCancellation() throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(true);
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, 60000);
        try (SafeCloseable held = limit.acquire(normalCtx).join()) {
            final RequestCancellation cancellation = new RequestCancellation();
            final CompletableFuture<SafeCloseable> pending = limit.acquire(newContext(eventLoop, cancellation));
            eventLoop.timeoutStarted.get(5, TimeUnit.SECONDS);
            eventLoop.releaseTimeout.countDown();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConcurrencyLimitTimeoutException.class);
            cancellation.cancel(new AnticipatedException());
            final CompletableFuture<SafeCloseable> replacement = queueSingleReplacement(limit);
            held.close();
            replacement.get(5, TimeUnit.SECONDS).close();
            assertThat(limit.acquiredPermits()).isZero();
        } finally {
            eventLoop.releaseTimeout.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void alreadyCancelledAcquisitionDoesNotConsumeCapacity() throws Exception {
        final ControlledEventLoop eventLoop = new ControlledEventLoop(false);
        try {
            final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, () -> 1, 1, 60000);
            final RequestCancellation cancellation = new RequestCancellation();
            final Throwable cause = new AnticipatedException();
            cancellation.cancel(cause);
            assertThatThrownBy(() -> limit.acquire(newContext(eventLoop, cancellation)).join()).hasCause(cause);
            assertThat(limit.acquiredPermits()).isZero();
            assertThat(eventLoop.scheduled).isNotDone();
            limit.acquire(normalCtx).get(5, TimeUnit.SECONDS).close();
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    private CompletableFuture<SafeCloseable> queueSingleReplacement(DefaultConcurrencyLimit limit) {
        final CompletableFuture<SafeCloseable> replacement = limit.acquire(normalCtx);
        assertThat(replacement).isNotDone();
        assertThatThrownBy(() -> limit.acquire(normalCtx).join())
                .hasCauseInstanceOf(TooManyPendingAcquisitionsException.class);
        return replacement;
    }

    private static ClientRequestContext newContext(DefaultEventLoop eventLoop,
                                                   RequestCancellation cancellation) {
        final RequestOptionsBuilder options = RequestOptions.builder();
        cancellation.attach(options);
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .eventLoop(eventLoop)
                                   .requestOptions(options.build())
                                   .build();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class ControlledEventLoop extends DefaultEventLoop {

        private final boolean blockTimeout;
        final CompletableFuture<ScheduledFuture<?>> scheduled = new CompletableFuture<>();
        final CompletableFuture<Void> timeoutStarted = new CompletableFuture<>();
        final CountDownLatch releaseTimeout = new CountDownLatch(1);

        ControlledEventLoop(boolean blockTimeout) {
            this.blockTimeout = blockTimeout;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            final Runnable task = blockTimeout ? () -> {
                timeoutStarted.complete(null);
                awaitRelease(releaseTimeout);
                command.run();
            } : command;
            final ScheduledFuture<?> future = super.schedule(task, blockTimeout ? 0 : delay, unit);
            scheduled.complete(future);
            return future;
        }
    }
}
