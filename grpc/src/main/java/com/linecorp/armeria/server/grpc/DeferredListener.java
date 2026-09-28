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

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.CallExecutor;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;

import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;

final class DeferredListener<I> extends ServerCall.Listener<I> {

    private static final Logger logger = LoggerFactory.getLogger(DeferredListener.class);

    private final AbstractServerCall<I, ?> serverCall;
    private final CallExecutor callExecutor;

    // The following fields are intentionally non-volatile because they are accessed only by the tasks
    // running on `callExecutor`.
    @Nullable
    private ArrayDeque<Consumer<Listener<I>>> pendingQueue = new ArrayDeque<>();

    @Nullable
    private Listener<I> delegate;
    private boolean callClosed;

    DeferredListener(ServerCall<I, ?> serverCall, CompletableFuture<ServerCall.Listener<I>> listenerFuture) {
        final AbstractServerCall<I, ?> armeriaServerCall = ServerCallUtil.findArmeriaServerCall(serverCall);
        checkState(armeriaServerCall != null, "Cannot use %s with a non-Armeria gRPC server. ServerCall: %s",
                   AsyncServerInterceptor.class.getName(), serverCall);
        this.serverCall = armeriaServerCall;
        callExecutor = armeriaServerCall.callExecutor();

        listenerFuture.handleAsync((delegate, cause) -> {
            if (cause != null) {
                callClosed = true;
                armeriaServerCall.close(cause);
                return null;
            }

            this.delegate = delegate;
            assert pendingQueue != null;
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
        }, callExecutor);
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
        if (callExecutor.inExecutor()) {
            // Already part of the current task. Resubmitting would let later callbacks overtake this one.
            processTask(task);
        } else {
            callExecutor.execute(() -> {
                try {
                    processTask(task);
                } catch (Throwable cause) {
                    callClosed = true;
                    if (!serverCall.isCloseCalled()) {
                        serverCall.close(cause);
                    } else {
                        logger.warn("Error in deferred gRPC listener callback.", cause);
                    }
                }
            });
        }
    }

    private void processTask(Consumer<ServerCall.Listener<I>> task) {
        if (callClosed) {
            return;
        }
        if (shouldBePending()) {
            addPendingTask(task);
        } else {
            task.accept(delegate);
        }
    }

    private void addPendingTask(Consumer<ServerCall.Listener<I>> task) {
        assert pendingQueue != null;
        pendingQueue.add(task);
    }

    private boolean shouldBePending() {
        return delegate == null;
    }
}
