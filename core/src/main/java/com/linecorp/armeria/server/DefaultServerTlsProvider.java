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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.util.concurrent.EventExecutor;

final class DefaultServerTlsProvider implements AutoCloseable {

    private final ServerTlsProvider delegate;
    private final SslContextFactory sslContextFactory;

    DefaultServerTlsProvider(ServerTlsProvider delegate, SslContextFactory sslContextFactory) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.sslContextFactory = requireNonNull(sslContextFactory, "sslContextFactory");
    }

    private CompletableFuture<ServerTlsSpec> serverTlsSpec(ConnectionContext ctx) {
        try {
            return requireNonNull(delegate.serverTlsSpec(ctx), "ServerTlsProvider.serverTlsSpec()")
                    .thenApply(result -> requireNonNull(result, "result"));
        } catch (Exception e) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(e);
        }
    }

    CompletableFuture<ServerTlsSpec> serverTlsSpec(ConnectionContext ctx, EventExecutor executor) {
        final CompletableFuture<ServerTlsSpec> cf = serverTlsSpec(ctx);
        if (cf.isDone()) {
            return cf;
        }
        final CompletableFuture<ServerTlsSpec> cf0 = new CompletableFuture<>();
        cf.whenComplete((v, t) -> serverTlsSpec0(v, t, executor, cf0));
        return cf0;
    }

    private static void serverTlsSpec0(@Nullable ServerTlsSpec serverTlsSpec, @Nullable Throwable t,
                                       EventExecutor executor, CompletableFuture<ServerTlsSpec> cf) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> serverTlsSpec0(serverTlsSpec, t, executor, cf));
            return;
        }
        if (t != null) {
            cf.completeExceptionally(t);
            return;
        }
        if (serverTlsSpec == null) {
            cf.completeExceptionally(new NullPointerException("serverTlsSpec"));
            return;
        }
        cf.complete(serverTlsSpec);
    }

    SslContextFactory sslContextFactory() {
        return sslContextFactory;
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable) {
            ((AutoCloseable) delegate).close();
        }
    }
}
