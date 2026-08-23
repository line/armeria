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

import io.netty.channel.EventLoop;

/**
 * A per-call {@link Executor} that runs the tasks of a single gRPC call one at a time.
 *
 * <ul>
 *   <li>No two tasks run concurrently, and consecutive tasks have a happens-before relationship.</li>
 *   <li>Tasks run in the order they were enqueued, whichever thread submitted them.</li>
 *   <li>A task submitted while this executor is already running a task on the current thread is
 *       enqueued and runs after the current task returns, on the same thread. It is neither run inline
 *       nor handed back to the underlying executor.</li>
 *   <li>A {@link Throwable} thrown by a task goes to the exception handler; one thrown by the handler
 *       itself is logged. Neither stops the remaining tasks.</li>
 *   <li>If the underlying executor rejects a submission, the task never runs and the
 *       {@link java.util.concurrent.RejectedExecutionException} propagates, unless a drain already
 *       running on this executor's thread took the task first, in which case it runs and
 *       {@code execute()} returns normally.</li>
 * </ul>
 */
public interface CallExecutor extends Executor {

    /**
     * Returns a {@link CallExecutor} that runs tasks on the specified {@link EventLoop}.
     * When called on the event loop while idle, a task runs inline without touching the queue.
     */
    static CallExecutor of(EventLoop eventLoop, Consumer<? super Throwable> exceptionHandler) {
        return new EventLoopCallExecutor(requireNonNull(eventLoop, "eventLoop"),
                                         requireNonNull(exceptionHandler, "exceptionHandler"));
    }

    /**
     * Returns a {@link CallExecutor} that serializes tasks on top of the specified, possibly
     * multi-threaded, {@link Executor} such as {@code ctx.blockingTaskExecutor()}.
     */
    static CallExecutor sequential(Executor executor, Consumer<? super Throwable> exceptionHandler) {
        return new SequentialCallExecutor(requireNonNull(executor, "executor"),
                                          requireNonNull(exceptionHandler, "exceptionHandler"));
    }

    /**
     * Returns {@code true} if the current thread is the one this executor runs its tasks on,
     * like {@link EventLoop#inEventLoop()}.
     */
    boolean inExecutor();
}
