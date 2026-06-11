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

/**
 * A {@link ServerTlsProvider} that tries a primary provider first, and if it returns
 * {@code null}, falls back to a secondary provider.
 */
final class FallbackServerTlsProvider implements ServerTlsProvider, AutoCloseable {

    private final ServerTlsProvider primary;
    private final ServerTlsProvider fallback;

    FallbackServerTlsProvider(ServerTlsProvider primary, ServerTlsProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<@Nullable ServerTlsSpec> serverTlsSpec(ConnectionContext ctx) {
        final CompletableFuture<@Nullable ServerTlsSpec> first = requireNonNull(
                primary.serverTlsSpec(ctx), "ServerTlsProvider.serverTlsSpec");
        return first.thenCompose(spec -> {
            if (spec != null) {
                return UnmodifiableFuture.completedFuture(spec);
            }
            return fallback.serverTlsSpec(ctx);
        });
    }

    @Override
    public void close() throws Exception {
        if (primary instanceof AutoCloseable) {
            ((AutoCloseable) primary).close();
        }
        if (fallback instanceof AutoCloseable) {
            ((AutoCloseable) fallback).close();
        }
    }
}
