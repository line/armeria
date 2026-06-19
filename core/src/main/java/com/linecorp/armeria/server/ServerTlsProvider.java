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
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * Resolves TLS configuration from a {@link ConnectionContext} for each new connection.
 * Unlike {@link com.linecorp.armeria.common.TlsProvider TlsProvider} which resolves TLS
 * by hostname alone, this interface has access to full connection-level properties
 * (SNI hostname, ALPN protocols, remote address, etc.).
 *
 * <p>Example usage:
 * <pre>{@code
 * Server.builder()
 *       .tlsProvider(ServerTlsProvider.of(ctx -> {
 *           return ServerTlsSpec.builder()
 *                               .tlsKeyPair(keyPair)
 *                               .build();
 *       }))
 *       .service("/api", myService)
 *       .build();
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface ServerTlsProvider {

    /**
     * Creates a {@link ServerTlsProvider} from a synchronous {@link Function}.
     */
    static ServerTlsProvider of(Function<? super ConnectionContext, @Nullable ServerTlsSpec> function) {
        requireNonNull(function, "function");
        return ctx -> UnmodifiableFuture.completedFuture(function.apply(ctx));
    }

    /**
     * Returns a {@link ServerTlsSpec} for the given {@link ConnectionContext}, or {@code null}
     * if this provider cannot handle the connection. When {@code null} is returned,
     * the server falls back to per-VirtualHost TLS settings.
     *
     * <p>This method is called by the server pipeline for each new TLS connection.
     * Implementations can inspect connection properties such as SNI hostname, ALPN protocols,
     * and custom attributes to determine the appropriate TLS configuration.
     */
    CompletableFuture<@Nullable ServerTlsSpec> serverTlsSpec(ConnectionContext ctx);
}
