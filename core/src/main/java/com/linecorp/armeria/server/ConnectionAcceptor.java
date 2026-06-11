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

import static com.linecorp.armeria.server.DefaultConnectionAcceptor.AlwaysAcceptConnectionAcceptor;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A callback that determines whether to accept or reject a newly established connection.
 * It is invoked once per connection for both TLS and cleartext (plain HTTP) connections.
 * For TLS connections, it runs <b>before</b> TLS negotiation begins and provides access to
 * SNI hostname and offered ALPN protocols. For cleartext connections, those TLS-specific
 * properties will be absent, but connection-level properties such as the remote address
 * are still available.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * sb.connectionAcceptor(ConnectionAcceptor.of(ctx -> {
 *     if ("blocked.example.com".equals(ctx.sniHostname())) {
 *         return false; // reject
 *     }
 *     return true;
 * }));
 * }</pre>
 *
 * @see ConnectionContext
 * @see ServerBuilder#connectionAcceptor(ConnectionAcceptor)
 */
@UnstableApi
@FunctionalInterface
public interface ConnectionAcceptor {

    /**
     * Returns a {@link ConnectionAcceptor} that always accepts connections.
     */
    static ConnectionAcceptor always() {
        return AlwaysAcceptConnectionAcceptor.INSTANCE;
    }

    /**
     * Creates a {@link ConnectionAcceptor} from a synchronous {@link Predicate}.
     *
     * @param predicate a predicate that returns {@code true} to accept or {@code false} to reject
     */
    static ConnectionAcceptor of(Predicate<? super ConnectionContext> predicate) {
        requireNonNull(predicate, "predicate");
        return ctx -> UnmodifiableFuture.completedFuture(predicate.test(ctx));
    }

    /**
     * Determines whether to accept or reject the connection.
     *
     * @param ctx the {@link ConnectionContext} for the new connection
     * @return a {@link CompletableFuture} resolving to {@code true} to accept or
     *         {@code false} to reject. Must not resolve to {@code null}.
     */
    CompletableFuture<Boolean> accept(ConnectionContext ctx);
}
