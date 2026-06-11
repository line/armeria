/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.channel.EventLoop;

final class DefaultConnectionAcceptor implements ConnectionAcceptor {

    private final ConnectionAcceptor delegate;

    DefaultConnectionAcceptor(ConnectionAcceptor delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    boolean isNoop() {
        return delegate == AlwaysAcceptConnectionAcceptor.INSTANCE;
    }

    @Override
    public CompletableFuture<Boolean> accept(ConnectionContext ctx) {
        try {
            final CompletableFuture<Boolean> future =
                    requireNonNull(delegate.accept(ctx), "ConnectionAcceptor.accept()");
            return future.thenApply(result -> requireNonNull(result, "result"));
        } catch (Exception e) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(e);
        }
    }

    CompletableFuture<Boolean> accept(ConnectionContext ctx, EventLoop eventLoop) {
        final CompletableFuture<Boolean> future = accept(ctx);
        if (future.isDone()) {
            return future;
        }
        final CompletableFuture<Boolean> f = new CompletableFuture<>();
        future.whenComplete((v, t) -> tryComplete(v, t, f, eventLoop));
        return f;
    }

    private static void tryComplete(@Nullable Boolean v, @Nullable Throwable t,
                                    CompletableFuture<Boolean> future, EventLoop eventLoop) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> tryComplete(v, t, future, eventLoop));
            return;
        }
        if (t != null) {
            future.completeExceptionally(t);
        } else {
            future.complete(firstNonNull(v, false));
        }
    }

    enum AlwaysAcceptConnectionAcceptor implements ConnectionAcceptor {

        INSTANCE;

        @Override
        public CompletableFuture<Boolean> accept(ConnectionContext ctx) {
            return UnmodifiableFuture.completedFuture(true);
        }
    }
}
