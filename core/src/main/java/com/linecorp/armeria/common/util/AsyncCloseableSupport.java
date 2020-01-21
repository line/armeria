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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;

/**
 * Provides support for implementing {@link AsyncCloseable} or {@link ListenableAsyncCloseable}.
 */
public final class AsyncCloseableSupport implements ListenableAsyncCloseable {

    private static final AtomicIntegerFieldUpdater<AsyncCloseableSupport> closingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AsyncCloseableSupport.class, "closing");

    private static final AsyncCloseableSupport CLOSED;

    static {
        CLOSED = of();
        CLOSED.closeAsync();
    }

    /**
     * Returns a new {@link AsyncCloseableSupport} that will be completed immediately on {@link #close()} or
     * {@link #closeAsync()}. This method is useful when you don't have any resources to release.
     * <pre>{@code
     * > public class MyClass implements ListenableAsyncCloseable {
     * >     final AsyncCloseableSupport closeable = AsyncCloseableSupport.of();
     * >
     * >     public void doSomething() {
     * >         if (closeable.isClosing()) {
     * >             throw new IllegalStateException("Closed already");
     * >         }
     * >         ...
     * >     }
     * >
     * >     @Override
     * >     public boolean isClosing() { return closeable.isClosing(); }
     * >
     * >     @Override
     * >     public CompletableFuture<?> whenClosed() { return closeable.whenClosed(); }
     * >
     * >     @Override
     * >     public CompletableFuture<?> closeAsync() { return closeable.closeAsync(); }
     * >
     * >     @Override
     * >     public void close() { closeable.close(); }
     * > }
     * }</pre>
     */
    public static AsyncCloseableSupport of() {
        return of(f -> f.complete(null));
    }

    /**
     * Returns a new {@link AsyncCloseableSupport} which calls the specified {@link Consumer} on
     * {@link #close()} or {@link #closeAsync()}.
     * <pre>{@code
     * > class MyClass implements ListenableAsyncCloseable {
     * >     final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(f -> {
     * >         // Release resources here.
     * >         ...
     * >         f.complete(null);
     * >     });
     * >
     * >     @Override
     * >     public boolean isClosing() { return closeable.isClosing(); }
     * >
     * >     @Override
     * >     public CompletableFuture<?> whenClosed() { return closeable.whenClosed(); }
     * >
     * >     @Override
     * >     public CompletableFuture<?> closeAsync() { return closeable.closeAsync(); }
     * >
     * >     @Override
     * >     public void close() { closeable.close(); }
     * > }
     * }</pre>
     *
     * @param closeAction the {@link Consumer} which performs the task that release the resources and
     *                    completes the given {@link CompletableFuture}.
     */
    public static AsyncCloseableSupport of(Consumer<CompletableFuture<?>> closeAction) {
        return new AsyncCloseableSupport(requireNonNull(closeAction, "closeAction"));
    }

    /**
     * Returns the {@link AsyncCloseableSupport} which has been closed already.
     */
    public static AsyncCloseableSupport closed() {
        return CLOSED;
    }

    private final Consumer<CompletableFuture<?>> closeAction;
    private final CompletableFuture<?> closeFuture = new CompletableFuture<>();
    private final UnmodifiableFuture<?> unmodifiableCloseFuture =
            UnmodifiableFuture.wrap(closeFuture);
    private volatile int closing;

    private AsyncCloseableSupport(Consumer<CompletableFuture<?>> closeAction) {
        this.closeAction = closeAction;
    }

    @Override
    public boolean isClosing() {
        return closing != 0;
    }

    @Override
    public boolean isClosed() {
        return closeFuture.isDone();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        if (setClosing()) {
            invokeCloseAction();
        }
        return whenClosed();
    }

    @Override
    public void close() {
        final boolean setClosing = setClosing();
        if (setClosing) {
            invokeCloseAction();
        }

        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    closeFuture.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (CancellationException e) {
                    // Throw the exception only for the first.
                    if (setClosing) {
                        throw e;
                    } else {
                        break;
                    }
                } catch (ExecutionException e) {
                    // Throw the exception only for the first.
                    if (setClosing) {
                        throw new CompletionException(e.getCause());
                    } else {
                        break;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean setClosing() {
        return closingUpdater.compareAndSet(this, 0, -1);
    }

    private void invokeCloseAction() {
        try {
            closeAction.accept(closeFuture);
        } catch (Throwable cause) {
            closeFuture.completeExceptionally(cause);
        }
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return unmodifiableCloseFuture;
    }
}
