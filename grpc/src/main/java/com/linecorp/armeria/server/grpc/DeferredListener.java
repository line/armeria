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

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;

import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.netty.util.concurrent.EventExecutor;

final class DeferredListener<I> extends ServerCall.Listener<I> {
    @Nullable
    private final Executor blockingExecutor;
    @Nullable
    private final EventExecutor eventLoop;

    // The following values are intentionally non-volatile, although the callback methods, which can be called
    // by non-`sequentialExecutor()` thread, access the values. Because `maybeAddPendingTask()` double-checks
    // the status of the values in the `sequentialExecutor()`.
    @Nullable
    private ArrayDeque<Consumer<Listener<I>>> pendingQueue = new ArrayDeque<>();

    @Nullable
    private Listener<I> delegate;
    private boolean callClosed;

    DeferredListener(ServerCall<I, ?> serverCall, CompletableFuture<ServerCall.Listener<I>> listenerFuture) {
        final AbstractServerCall<I, ?> armeriaServerCall = ServerCallUtil.findArmeriaServerCall(serverCall);
        checkState(armeriaServerCall != null, "Cannot use %s with a non-Armeria gRPC server. ServerCall: %s",
                   AsyncServerInterceptor.class.getName(), serverCall);
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
                    final Consumer<Listener<I>> task = pendingQueue.poll();
                    if (task != null) {
                        task.accept(delegate);
                    } else {
                        break;
                    }
                }
            } catch (Throwable ex) {
                callClosed = true;
                armeriaServerCall.close(ex);
                return null;
            } finally {
                pendingQueue = null;
            }
            return null;
        }, sequentialExecutor());
    }

    @Override
    public void onMessage(I message) {
        maybeAddPendingTask(listener -> listener.onMessage(message));
    }

    @Override
    public void onHalfClose() {
        maybeAddPendingTask(Listener::onHalfClose);
    }

    @Override
    public void onCancel() {
        maybeAddPendingTask(Listener::onCancel);
    }

    @Override
    public void onComplete() {
        maybeAddPendingTask(Listener::onComplete);
    }

    @Override
    public void onReady() {
        maybeAddPendingTask(Listener::onReady);
    }

    private void maybeAddPendingTask(Consumer<ServerCall.Listener<I>> task) {
        if (callClosed) {
            return;
        }

        if (!shouldBePending()) {
            task.accept(delegate);
            return;
        }

        if (eventLoop != null && eventLoop.inEventLoop()) {
            addPendingTask(task);
        } else {
            // It is unavoidable to reschedule the task to ensure the execution order.
            sequentialExecutor().execute(() -> {
                if (callClosed) {
                    return;
                }
                if (!shouldBePending()) {
                    task.accept(delegate);
                } else {
                    addPendingTask(task);
                }
            });
        }
    }

    private void addPendingTask(Consumer<ServerCall.Listener<I>> task) {
        pendingQueue.add(task);
    }

    private boolean shouldBePending() {
        return delegate == null;
    }

    private Executor sequentialExecutor() {
        return firstNonNull(eventLoop, blockingExecutor);
    }
}
