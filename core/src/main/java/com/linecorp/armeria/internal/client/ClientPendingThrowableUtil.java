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
 *                  .contextCustomizer(ctx -> setPendingThrowable(ctx, e))
 *                  .decorator(LoggingClient.newDecorator()) // the request is logged
 *                  .build();
 * assertThatThrownBy(() -> webClient.blocking().get("/"))
 *         .isInstanceOf(UnprocessedRequestException.class)
 *         .hasCause(e);
 * }</pre>
 */
public final class ClientPendingThrowableUtil {

    private static final AttributeKey<Throwable> CLIENT_PENDING_THROWABLE =
            AttributeKey.valueOf(ClientPendingThrowableUtil.class, "CLIENT_PENDING_THROWABLE");

    /**
     * Sets a pending {@link Throwable} for the specified {@link ClientRequestContext}.
     * Note that the throwable will be peeled via {@link Exceptions#peel(Throwable)}
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
        ctx.setAttr(CLIENT_PENDING_THROWABLE, cause);
    }

    /**
     * Retrieves the pending {@link Throwable} for the specified {@link ClientRequestContext}.
     * Note that the derived context will also contain this attribute by default, and can fail
     * requests immediately. The pending {@link Throwable} can be removed by
     * {@link #removePendingThrowable(ClientRequestContext)}.
     *
     * <p>For example:<pre>{@code
     * ClientRequestContext ctx = ...;
     * Throwable t = ...;
     * final Throwable t1 = pendingThrowable(ctx);
     * assert t1 == null;
     * setPendingThrowable(ctx, t);
     * final Throwable t2 = pendingThrowable(ctx);
     * assert t2 == t;
     * final ClientRequestContext derived = ctx.newDerivedContext(id, req, rpcReq, endpoint);
     * final Throwable t3 = pendingThrowable(derived);
     * assert t3 == t;
     * }</pre>
     */
    @Nullable
    public static Throwable pendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(CLIENT_PENDING_THROWABLE);
    }

    /**
     * Removes the pending throwable set by {@link #setPendingThrowable(ClientRequestContext, Throwable)}.
     *
     * <p>For example:<pre>{@code
     * final Throwable throwable = pendingThrowable(ctx);
     * assert throwable != null;
     * assertThat(throwable).isEqualTo(e);
     * removePendingThrowable(ctx);
     * assertThat(pendingThrowable(ctx)).isNull();
     * }</pre>
     */
    public static void removePendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        if (!ctx.hasAttr(CLIENT_PENDING_THROWABLE)) {
            return;
        }
        ctx.setAttr(CLIENT_PENDING_THROWABLE, null);
    }

    private ClientPendingThrowableUtil() {}
}
