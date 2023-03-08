/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.netty.util.concurrent.EventExecutor;

final class DeferredListener<I> extends ServerCall.Listener<I> {

    private static final List<?> NOOP_TASKS = ImmutableList.of();

    @Nullable
    private final Executor blockingExecutor;
    @Nullable
    private final EventExecutor eventLoop;

    // The following values are intentionally non-volatile, although the callback methods, which can be called
    // by non-`sequentialExecutor()` thread, access the values. Because `maybeAddPendingTask()` double-checks
    // the status of the values in the `sequentialExecutor()`.
    private List<Consumer<Listener<I>>> pendingTasks = new ArrayList<>();
    @Nullable
    private Listener<I> delegate;
    private boolean callClosed;

    DeferredListener(ServerCall<I, ?> serverCall, CompletableFuture<ServerCall.Listener<I>> listenerFuture) {
        checkState(serverCall instanceof AbstractServerCall, "Cannot use %s with a non-Armeria gRPC server",
                   AsyncServerInterceptor.class.getName());
        @SuppressWarnings("unchecked")
        final AbstractServerCall<I, ?> armeriaServerCall = (AbstractServerCall<I, ?>) serverCall;

        // As per `ServerCall.Listener`'s Javadoc, the caller should call one simultaneously. `blockingExecutor`
        // is a sequential executor which is wrapped by `MoreExecutors.newSequentialExecutor()`. So both
        // `blockingExecutor` and `eventLoop` guarantees the execution order.
        blockingExecutor = armeriaServerCall.blockingExecutor();
        if (blockingExecutor == null) {
            eventLoop = armeriaServerCall.eventLoop();
        } else {
            eventLoop = null;
        }

        listenerFuture.handleAsync((delegate, cause) -> {
            if (cause != null) {
                callClosed = true;
                armeriaServerCall.close(cause);
                return null;
            }

            this.delegate = delegate;
            try {
                for (;;) {
                    final List<Consumer<Listener<I>>> pendingTasks = this.pendingTasks;
                    if (pendingTasks.isEmpty()) {
                        break;
                    }

                    // New pending tasks could be added while invoking pending tasks.
                    this.pendingTasks = new ArrayList<>();
                    try {
                        for (Consumer<Listener<I>> task : pendingTasks) {
                            task.accept(delegate);
                        }
                    } catch (Throwable ex) {
                        callClosed = true;
                        armeriaServerCall.close(ex);
                        return null;
                    }
                }
            } catch (Throwable ex) {
                callClosed = true;
                armeriaServerCall.close(ex);
                return null;
            }
            //noinspection unchecked
            pendingTasks = (List<Consumer<Listener<I>>>) NOOP_TASKS;
            return null;
        }, sequentialExecutor());
    }

    @Override
    public void onMessage(I message) {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            // listenerFuture has completed successfully. No race with the listenerFuture's callback.
            delegate.onMessage(message);
        } else {
            maybeAddPendingTask(listener -> listener.onMessage(message));
        }
    }

    @Override
    public void onHalfClose() {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            delegate.onHalfClose();
        } else {
            maybeAddPendingTask(Listener::onHalfClose);
        }
    }

    @Override
    public void onCancel() {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            delegate.onCancel();
        } else {
            maybeAddPendingTask(Listener::onCancel);
        }
    }

    @Override
    public void onComplete() {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            delegate.onComplete();
        } else {
            maybeAddPendingTask(Listener::onComplete);
        }
    }

    @Override
    public void onReady() {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            delegate.onReady();
        } else {
            maybeAddPendingTask(Listener::onReady);
        }
    }

    private void maybeAddPendingTask(Consumer<ServerCall.Listener<I>> task) {
        if (eventLoop != null && eventLoop.inEventLoop()) {
            pendingTasks.add(task);
        } else {
            // It is unavoidable to reschedule the task to ensure the execution order.
            sequentialExecutor().execute(() -> {
                if (callClosed) {
                    return;
                }
                if (pendingTasks == NOOP_TASKS) {
                    task.accept(delegate);
                } else {
                    pendingTasks.add(task);
                }
            });
        }
    }

    private Executor sequentialExecutor() {
        return firstNonNull(eventLoop, blockingExecutor);
    }
}
