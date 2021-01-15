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

package com.linecorp.armeria.internal.common.auth.oauth2;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Executes submitted {@link Callable} actions asynchronously in sequence.
 */
@UnstableApi
public final class SerialFuture {

    private final Queue<Runnable> actions = new ConcurrentLinkedQueue<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Consumer<Runnable> runner;

    @Nullable
    private volatile Runnable active;

    /**
     * Constructs {@link SerialFuture} with a supplied {@link Executor}.
     * @param executor An {@link Executor} to execute asynchronous actions.
     */
    public SerialFuture(@Nullable Executor executor) {
        runner = executor == null ? Runnable::run
                                  : r -> CompletableFuture.runAsync(r, executor);
    }

    /**
     * Constructs {@link SerialFuture}.
     */
    public SerialFuture() {
        this(null);
    }

    /**
     * Executes submitted {@link Callable} action asynchronously in sequence.
     * @param action An asynchronous {@link Callable} action to be executed in sequence.
     *               It returns {@link CompletionStage} that produces the final result when the action
     *               completes.
     * @return Returns a new {@link CompletionStage} with the same result or exception as the {@code action},
     *         that executes the given action when this stage completes.
     */
    public <V> CompletionStage<V> executeAsync(Callable<CompletionStage<V>> action) {
        final CompletableFuture<V> result = new CompletableFuture<>();
        actions.add(() -> {
            final CompletionStage<V> future;
            try {
                future = action.call();
            } catch (Throwable e) {
                result.completeExceptionally(Exceptions.peel(e));
                executeNext();
                return;
            }
            // replaced CompletableFuture.whenComplete() with CompletableFuture.handle()
            // due to performance issue described at
            // <a href="https://github.com/line/armeria/pull/1440">#1440</a>.
            future.handle((v, ex) -> {
                if (ex == null) {
                    result.complete(v);
                } else {
                    result.completeExceptionally(ex);
                }
                executeNext();
                //noinspection ReturnOfNull
                return null;
            });
        });

        executeNextIfNoActive();
        return result;
    }

    /**
     * Calls submitted {@link Callable} action asynchronously in sequence.
     * @param action A {@link Callable} action to be called asynchronously in sequence.
     *               It returns that produces the final result of computation.
     * @return Returns a new {@link CompletionStage} with the same result or exception as the {@code action},
     *         that executes the given action when this stage completes.
     */
    public <V> CompletionStage<V> callAsync(Callable<V> action) {
        final CompletableFuture<V> result = new CompletableFuture<>();
        actions.add(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(Exceptions.peel(e));
            } finally {
                executeNext();
            }
        });

        executeNextIfNoActive();
        return result;
    }

    /**
     * Continues to the next item execution only if the {@code active} item not set.
     * Check the {@code active} item in non-blocking manner.
     */
    private void executeNextIfNoActive() {
        final Runnable active;
        final ReadLock readLock = lock.readLock();
        readLock.lock();
        try {
            active = this.active;
        } finally {
            readLock.unlock();
        }
        if (active == null) {
            final WriteLock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (this.active == null) {
                    executeNext();
                }
            } finally {
                writeLock.unlock();
            }
        }
    }

    /**
     * Polls next item from the queue, resets the {@code active} item and passes it for execution
     * (done either directly/synchronously or using an {@link Executor}).
     */
    private void executeNext() {
        final Runnable active;
        final WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            active = actions.poll();
            this.active = active;
        } finally {
            writeLock.unlock();
        }
        if (active != null) {
            runner.accept(active);
        }
    }
}
