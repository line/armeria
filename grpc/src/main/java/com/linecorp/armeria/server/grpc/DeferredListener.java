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

final class DeferredListener<I> extends ServerCall.Listener<I> {

    private static final List<?> NOOP_TASKS = ImmutableList.of();

    private List<Consumer<Listener<I>>> pendingTasks = new ArrayList<>();
    @Nullable
    private Listener<I> delegate;
    private boolean callClosed;

    DeferredListener(ServerCall<I, ?> serverCall, CompletableFuture<ServerCall.Listener<I>> future) {
        checkState(serverCall instanceof AbstractServerCall, "Cannot use %s with non-Armeria gRPC server",
                   AsyncServerInterceptor.class.getName());
        final AbstractServerCall<I, ?> armeriaServerCall = (AbstractServerCall<I, ?>) serverCall;

        // `sequentialExecutor` is used to invoke callbacks of ServerCall.Listener by FramedGrpcService and
        // Armeria's ServerCall implementations. So thread-safety and the execution order are guaranteed.
        Executor sequentialExecutor = armeriaServerCall.blockingExecutor();
        if (sequentialExecutor == null) {
            sequentialExecutor = armeriaServerCall.eventLoop();
        }

        future.handleAsync((delegate, cause) -> {
            if (cause != null) {
                callClosed = true;
                armeriaServerCall.close(cause);
                return null;
            }

            this.delegate = delegate;
            try {
                for (Consumer<Listener<I>> task : pendingTasks) {
                    task.accept(delegate);
                }
            } catch (Throwable ex) {
                callClosed = true;
                armeriaServerCall.close(ex);
            }
            //noinspection unchecked
            pendingTasks = (List<Consumer<Listener<I>>>) NOOP_TASKS;
            return null;
        }, sequentialExecutor);
    }

    @Override
    public void onMessage(I message) {
        if (callClosed) {
            return;
        }

        if (pendingTasks == NOOP_TASKS) {
            delegate.onMessage(message);
        } else {
            pendingTasks.add(listener -> listener.onMessage(message));
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
            pendingTasks.add(Listener::onHalfClose);
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
            pendingTasks.add(Listener::onCancel);
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
            pendingTasks.add(Listener::onComplete);
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
            pendingTasks.add(Listener::onReady);
        }
    }
}
