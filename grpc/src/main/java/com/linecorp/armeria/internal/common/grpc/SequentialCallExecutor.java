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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link CallExecutor} that serializes its tasks on top of a possibly multi-threaded {@link Executor},
 * typically {@code ctx.blockingTaskExecutor()}.
 *
 * <p>Guava's {@link MoreExecutors#newSequentialExecutor(Executor)} already provides the queueing policy:
 * FIFO, a reentrant submission runs after the current task on the same worker, and a rejected submission
 * is rolled back. This class only adds {@link #inExecutor()}, which Guava has no equivalent of, and
 * routes task exceptions to the handler instead of letting the worker swallow them.
 */
final class SequentialCallExecutor extends AbstractCallExecutor {

    private final Executor sequentialExecutor;

    /**
     * The thread that is currently running one of our tasks, or {@code null} if none is running.
     * Written only by the worker around each task; read from any thread by {@link #inExecutor()}.
     */
    @Nullable
    private volatile Thread currentThread;

    SequentialCallExecutor(Executor executor, Consumer<? super Throwable> exceptionHandler) {
        super(exceptionHandler);
        sequentialExecutor = MoreExecutors.newSequentialExecutor(executor);
    }

    @Override
    public void execute(Runnable task) {
        requireNonNull(task, "task");
        sequentialExecutor.execute(() -> {
            currentThread = Thread.currentThread();
            try {
                runTask(task);
            } finally {
                currentThread = null;
            }
        });
    }

    @Override
    public boolean inExecutor() {
        return currentThread == Thread.currentThread();
    }
}
