/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AttributeKey;

/**
 * Sets a pending {@link Throwable} for the specified {@link ClientRequestContext}.
 * This throwable will be thrown after all decorators are executed, but before the
 * actual client execution starts.
 *
 * <p>For example:<pre>{@code
 * final RuntimeException e = new RuntimeException();
 * final WebClient webClient =
 *         WebClient.builder(SessionProtocol.HTTP, endpointGroup)
 *                  .contextCustomizer(ctx -> setUnprocessedPendingThrowable(ctx, e))
 *                  .decorator(LoggingClient.newDecorator()) // the request is logged
 *                  .build();
 * assertThatThrownBy(() -> webClient.blocking().get("/"))
 *         .isInstanceOf(UnprocessedRequestException.class)
 *         .hasCause(e);
 * }</pre>
 */
public final class ClientPendingThrowableUtil {

    private static final AttributeKey<PendingThrowableContainer> CLIENT_PENDING_THROWABLE =
            AttributeKey.valueOf(ClientPendingThrowableUtil.class, "CLIENT_PENDING_THROWABLE");

    /**
     * Sets a pending {@link Throwable} for the specified {@link ClientRequestContext}.
     * Note that the set throwable will be peeled via {@link Exceptions#peel(Throwable)}
     * before being set.
     *
     * <p>For example:<pre>{@code
     * final RuntimeException e = new RuntimeException();
     * final CompletionException wrapper = new CompletionException(e);
     * final ClientRequestContext ctx = ...
     * setPendingThrowable(ctx, wrapper);
     * final Throwable throwable = pendingThrowable(ctx);
     * assert throwable != null;
     * assertThat(throwable).isEqualTo(e);
     * }</pre>
     */
    public static void setPendingThrowable(ClientRequestContext ctx, Throwable cause) {
        requireNonNull(ctx, "ctx");
        requireNonNull(cause, "cause");
        cause = Exceptions.peel(cause);
        final PendingThrowableContainer container = new PendingThrowableContainer(cause, ctx);
        ctx.setAttr(CLIENT_PENDING_THROWABLE, container);
    }

    /**
     * Retrieves the pending {@link Throwable} for the specified {@link ClientRequestContext}.
     * Note that <strong>only</strong> the attribute set for the specified {@link ClientRequestContext}
     * is returned. Even though a context contains this attribute, the derived context wouldn't
     * contain this attribute by default.
     *
     * <p>For example:<pre>{@code
     * ClientRequestContext ctx = null;
     * Throwable t;
     * final Throwable t1 = pendingThrowable(ctx); // null
     * setPendingThrowable(ctx, t);
     * final Throwable t2 = pendingThrowable(ctx); // t
     * final ClientRequestContext derived = ctx.newDerivedContext(id, req, rpcReq, endpoint);
     * final Throwable t3 = pendingThrowable(derived); // null
     * }</pre>
     */
    @Nullable
    public static Throwable pendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final PendingThrowableContainer container = ctx.attr(CLIENT_PENDING_THROWABLE);
        if (container == null || container.ctx != ctx) {
            return null;
        }
        return container.throwable;
    }

    /**
     * Transfers the value set by pending {@link Throwable} set by {@code from} to
     * {@code to}. If {@code from} doesn't contain the attribute, no action will occur.
     *
     * <p>Note that the {@link Throwable} is only transferred if {@code from} contains
     * the attribute as its own.
     *
     * <p>For example:<pre>{@code
     * final Throwable t1 = pendingThrowable(ctx); // null
     * setPendingThrowable(ctx, t);
     * final Throwable t2 = pendingThrowable(ctx); // t
     * final ClientRequestContext derived = ctx.newDerivedContext(id, req, rpcReq, endpoint);
     * final Throwable t3 = pendingThrowable(derived); // null
     * transferPendingThrowable(ctx, derived);
     * final Throwable t4 = pendingThrowable(derived); // t
     * }</pre>
     */
    public static void transferPendingThrowable(ClientRequestContext from,
                                                ClientRequestContext to) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        final Throwable throwable = pendingThrowable(from);
        if (throwable == null) {
            return;
        }
        final PendingThrowableContainer copy = new PendingThrowableContainer(throwable, to);
        to.setAttr(CLIENT_PENDING_THROWABLE, copy);
    }

    private static class PendingThrowableContainer {
        final Throwable throwable;
        final ClientRequestContext ctx;

        PendingThrowableContainer(Throwable throwable,
                                  ClientRequestContext ctx) {
            this.throwable = throwable;
            this.ctx = ctx;
        }
    }

    private ClientPendingThrowableUtil() {}
}
