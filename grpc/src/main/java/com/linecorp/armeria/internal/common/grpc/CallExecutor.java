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

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;

/**
 * Executes the tasks of a gRPC call sequentially and tracks whether the current thread is executing
 * one of them. Context propagation and task failure handling are the responsibility of the delegate
 * and the caller, respectively.
 */
public final class CallExecutor implements Executor {

    /**
     * Returns an executor that always submits tasks to the specified {@link EventLoop}, including
     * tasks submitted from that event loop, so that a reentrant submission runs after the current task
     * returns.
     */
    public static CallExecutor of(EventLoop eventLoop) {
        return new CallExecutor(requireNonNull(eventLoop, "eventLoop"));
    }

    /**
     * Returns an executor that executes tasks sequentially on the specified {@link Executor}.
     * Reentrant submissions run after the current task returns.
     */
    public static CallExecutor sequential(Executor executor) {
        return new CallExecutor(MoreExecutors.newSequentialExecutor(requireNonNull(executor, "executor")));
    }

    private final Executor delegate;
    @Nullable
    private volatile Thread currentThread;

    private CallExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        requireNonNull(task, "task");
        delegate.execute(() -> {
            currentThread = Thread.currentThread();
            try {
                task.run();
            } finally {
                currentThread = null;
            }
        });
    }

    /**
     * Returns whether the current thread is executing a task submitted to this executor.
     */
    public boolean inExecutor() {
        return currentThread == Thread.currentThread();
    }
}
