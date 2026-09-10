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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.netty.channel.EventLoop;

/**
 * A {@link CallExecutor} that runs its tasks on an {@link EventLoop}.
 *
 * <p>A single queue holds every task regardless of the submitting thread, and only the event loop
 * drains it, one drain at a time ({@code draining}). A task submitted while a drain is running on the
 * event loop is picked up by that drain after the current task returns. When the event loop is idle and
 * nothing is queued, the task runs inline without touching the queue.
 *
 * <p>Several calls share one event loop, so "on the event loop" is not enough to run inline: if another
 * request's context is current, the submission is handed off like one from a foreign thread,
 * so that the task runs in a fresh event loop turn with this call's context.
 */
final class EventLoopCallExecutor extends AbstractCallExecutor {

    private final EventLoop eventLoop;
    private final Runnable drainTask = this::drain;

    // Written from any thread, drained only from the event loop thread.
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

    // Accessed only from the event loop thread.
    private boolean draining;

    EventLoopCallExecutor(RequestContext ctx, Consumer<? super Throwable> exceptionHandler) {
        super(ctx, exceptionHandler);
        eventLoop = ctx.eventLoop();
    }

    @Override
    public void execute(Runnable task) {
        requireNonNull(task, "task");
        if (!eventLoop.inEventLoop() || !inCompatibleContext()) {
            executeFromForeignThread(task);
            return;
        }

        if (draining) {
            // Reentrant; the drain running further up the stack will pick it up.
            queue.add(task);
            return;
        }

        if (queue.isEmpty()) {
            // Anything a foreign thread enqueues from here on was submitted after this task, so FIFO holds.
            draining = true;
            try {
                runTask(task);
                drainQueue();
            } finally {
                draining = false;
            }
            return;
        }

        queue.add(task);
        drain();
    }

    private void executeFromForeignThread(Runnable task) {
        // Wrapped so that the rollback below removes exactly this submission,
        // as Guava's SequentialExecutor does.
        final Runnable submitted = new Runnable() {
            @Override
            public void run() {
                task.run();
            }

            @Override
            public String toString() {
                return task.toString();
            }
        };

        // Enqueued from this thread, not from the event loop task,
        // so that a reentrant submission cannot overtake it.
        queue.add(submitted);
        try {
            eventLoop.execute(drainTask);
        } catch (Throwable t) {
            // Still queued: it will never run, so rethrow. Already taken by a running drain:
            // it ran, and only the redundant drain request was rejected.
            if (queue.remove(submitted) || !(t instanceof RejectedExecutionException)) {
                throw t;
            }
        }
    }

    @Override
    public boolean inExecutor() {
        return eventLoop.inEventLoop() && draining;
    }

    /**
     * Whether {@link #ctx()} may be pushed on the current thread, mirroring the rule in
     * {@code ServiceRequestContext.push()}: no context, a context without a root,
     * this context or one of its children.
     */
    private boolean inCompatibleContext() {
        final RequestContext current = RequestContext.currentOrNull();
        if (current == null || current.root() == null) {
            return true;
        }
        final RequestContext ctx = ctx();
        return current.unwrapAll() == ctx.unwrapAll() ||
               RequestContextUtil.equalsIgnoreWrapper(current.root(), ctx.root());
    }

    @VisibleForTesting
    int pendingTasks() {
        return queue.size();
    }

    private void drain() {
        assert eventLoop.inEventLoop();
        if (draining) {
            // The drain running further up the stack will pick up whatever is queued.
            return;
        }
        draining = true;
        try {
            drainQueue();
        } finally {
            draining = false;
        }
    }

    private void drainQueue() {
        for (;;) {
            final Runnable next = queue.poll();
            if (next == null) {
                return;
            }
            runTask(next);
        }
    }
}
