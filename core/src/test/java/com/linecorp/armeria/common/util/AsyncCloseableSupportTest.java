/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.testing.AnticipatedException;

class AsyncCloseableSupportTest {

    @Test
    void initialState() {
        final AsyncCloseableSupport support = AsyncCloseableSupport.of();
        assertThat(support.isClosing()).isFalse();
        assertThat(support.isClosed()).isFalse();
        assertThat(support.whenClosed()).isNotDone();

        final AsyncCloseableSupport closedSupport = AsyncCloseableSupport.closed();
        assertThat(closedSupport.isClosing()).isTrue();
        assertThat(closedSupport.isClosed()).isTrue();
        assertThat(closedSupport.whenClosed()).isCompletedWithValue(null);
    }

    @Test
    void closeFutureReturnsSameFuture() {
        final AsyncCloseableSupport support = AsyncCloseableSupport.of();
        assertThat(support.whenClosed()).isSameAs(support.whenClosed());
    }

    @Test
    void closeFutureIsNotUpdatable() {
        final AsyncCloseableSupport support = AsyncCloseableSupport.of();
        final CompletableFuture<?> closeFuture = support.whenClosed();
        assertThatThrownBy(() -> closeFuture.complete(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> closeFuture.completeExceptionally(new Exception()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(closeFuture.cancel(false)).isFalse();
        assertThatThrownBy(() -> closeFuture.obtrudeValue(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> closeFuture.obtrudeException(new Exception()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void closeActionIsInvokedOnClose() {
        final AtomicBoolean invoked = new AtomicBoolean();
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(f -> {
            invoked.set(true);
            f.complete(null);
        });
        support.close();
        assertThat(invoked).isTrue();
        assertThat(support.whenClosed()).isCompletedWithValue(null);
    }

    @Test
    void closeActionCompletedExceptionally() {
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(f -> {
            f.completeExceptionally(new AnticipatedException());
        });

        assertThatThrownBy(support::close).isInstanceOf(CompletionException.class)
                                          .hasCauseInstanceOf(AnticipatedException.class);
    }

    @Test
    void closeActionFailed() {
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(f -> {
            throw new AnticipatedException();
        });

        assertThatThrownBy(support::close).isInstanceOf(CompletionException.class)
                                          .hasCauseInstanceOf(AnticipatedException.class);
    }

    @Test
    void secondCloseShouldDoNothing() {
        final RuntimeException exception = new RuntimeException();
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(f -> {
            throw exception;
        });

        assertThatThrownBy(support::close).hasCauseReference(exception);
        // No exception should be thrown.
        support.close();
    }

    @Test
    void stateTransitionAsync() {
        final AtomicReference<CompletableFuture<?>> futureCaptor = new AtomicReference<>();
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(futureCaptor::set);

        support.closeAsync();
        assertThat(support.isClosing()).isTrue();
        assertThat(support.isClosed()).isFalse();

        futureCaptor.get().complete(null);
        await().untilAsserted(() -> assertThat(support.isClosed()).isTrue());
    }

    @Test
    void stateTransitionSync() {
        final AtomicReference<CompletableFuture<?>> futureCaptor = new AtomicReference<>();
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(futureCaptor::set);

        final CompletableFuture<Void> syncCloseFuture = CompletableFuture.runAsync(support::close);
        await().untilAsserted(() -> assertThat(support.isClosing()).isTrue());
        assertThat(support.isClosed()).isFalse();
        assertThat(syncCloseFuture).isNotDone();

        futureCaptor.get().complete(null);
        await().untilAsserted(() -> assertThat(support.isClosed()).isTrue());
        syncCloseFuture.join();
    }

    @Test
    void interruptedClose() throws Exception {
        final AtomicReference<CompletableFuture<?>> futureCaptor = new AtomicReference<>();
        final BlockingQueue<Thread> threadCaptor = new LinkedTransferQueue<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AsyncCloseableSupport support = AsyncCloseableSupport.of(futureCaptor::set);

        final CompletableFuture<Void> syncCloseFuture = CompletableFuture.runAsync(() -> {
            threadCaptor.add(Thread.currentThread());
            support.close();
            interrupted.set(Thread.interrupted());
        });

        await().untilAsserted(() -> assertThat(support.isClosing()).isTrue());

        // Interrupt the thread that is calling close().
        final Thread thread = threadCaptor.take();
        thread.interrupt();

        // Wait a little bit until close() is interrupted.
        Thread.sleep(1000);

        // close() should still completed normally.
        futureCaptor.get().complete(null);
        await().untilAsserted(() -> assertThat(support.isClosed()).isTrue());
        syncCloseFuture.join();

        // Thread should remain interrupted.
        assertThat(interrupted).isTrue();
    }
}
