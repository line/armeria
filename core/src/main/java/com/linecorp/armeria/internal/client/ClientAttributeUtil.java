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
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AttributeKey;

/**
 * Contains helper methods used for armeria client internals.
 */
public final class ClientAttributeUtil {

    private static final AttributeKey<PendingThrowableContainer> UNPROCESSED_PENDING_THROWABLE =
            AttributeKey.valueOf(ClientAttributeUtil.class, "UNPROCESSED_PENDING_THROWABLE");

    /**
     * Sets a pending {@link Throwable} for the specified {@link ClientRequestContext}.
     *
     * <p>This throwable will be thrown after all decorators are executed, but before the
     * actual client execution starts. Note that the {@link Throwable} will be peeled using
     * {@link Exceptions#peel(Throwable)}, and wrapped by an {@link UnprocessedRequestException}.
     *
     * <p>For example:<pre>{@code
     * final RuntimeException e = new RuntimeException();
     * final WebClient webClient =
     *         WebClient.builder(SessionProtocol.HTTP, endpointGroup)
     *                  .contextCustomizer(ctx -> setUnprocessedPendingThrowable(ctx, e))
     *                  .build();
     * assertThatThrownBy(() -> webClient.blocking().get("/"))
     *         .isInstanceOf(UnprocessedRequestException.class)
     *         .hasCause(e);
     * }</pre>
     */
    public static void setUnprocessedPendingThrowable(ClientRequestContext ctx, Throwable cause) {
        requireNonNull(ctx, "ctx");
        requireNonNull(cause, "cause");
        final Throwable peeled = Exceptions.peel(cause);
        final PendingThrowableContainer container = new PendingThrowableContainer(peeled, ctx);
        ctx.setAttr(UNPROCESSED_PENDING_THROWABLE, container);
    }

    /**
     * Retrieves the pending {@link Throwable} for the specified {@link ClientRequestContext}.
     * Note that <strong>only</strong> the attribute set for the specified {@link ClientRequestContext}
     * is returned.
     *
     * <p>For instance, if contextA has this attribute set and contextB is derived from contextA
     * via {@link ClientRequestContext}, calling this method will return {@code null} for contextB.
     * This design is made to prevent inadvertent failures when using derived contexts.
     */
    @Nullable
    public static Throwable unprocessedPendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final PendingThrowableContainer container = ctx.attr(UNPROCESSED_PENDING_THROWABLE);
        if (container == null || container.ctx != ctx) {
            return null;
        }
        return container.throwable;
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

    private ClientAttributeUtil() {}
}
