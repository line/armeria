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

    private static final AttributeKey<Throwable> UNPROCESSED_PENDING_THROWABLE_KEY =
            AttributeKey.valueOf(ClientAttributeUtil.class, "UNPROCESSED_PENDING_THROWABLE_KEY");

    /**
     * Sets a pending {@link Throwable} for the specified {@link ClientRequestContext}.
     *
     * This throwable will be thrown after all decorators are executed, but before the
     * actual client execution starts. Note that the {@link Throwable} will be wrapped
     * by an {@link UnprocessedRequestException}.
     */
    public static void setUnprocessedPendingThrowable(ClientRequestContext ctx, Throwable cause) {
        requireNonNull(ctx, "ctx");
        requireNonNull(cause, "cause");
        ctx.setAttr(UNPROCESSED_PENDING_THROWABLE_KEY, Exceptions.peel(cause));
    }

    /**
     * Retrieves the pending {@link Throwable} for the specified {@link ClientRequestContext}.
     */
    @Nullable
    public static Throwable unprocessedPendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(UNPROCESSED_PENDING_THROWABLE_KEY);
    }

    /**
     * Removes the pending {@link Throwable} for the specified {@link ClientRequestContext}.
     */
    public static void removeUnprocessedPendingThrowable(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        if (ctx.hasAttr(UNPROCESSED_PENDING_THROWABLE_KEY)) {
            ctx.setAttr(UNPROCESSED_PENDING_THROWABLE_KEY, null);
        }
    }

    private ClientAttributeUtil() {}
}
