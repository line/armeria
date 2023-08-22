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

package com.linecorp.armeria.internal.client.websocket;

import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

public final class WebSocketClientUtil {

    private static final AttributeKey<Consumer<Throwable>> CLOSING_RESPONSE_TASK =
            AttributeKey.valueOf(WebSocketClientUtil.class, "CLOSING_RESPONSE_TASK");

    public static void setClosingResponseTask(ClientRequestContext ctx, Consumer<Throwable> task) {
        requireNonNull(ctx, "ctx");
        requireNonNull(task, "task");
        ctx.setAttr(CLOSING_RESPONSE_TASK, task);
    }

    public static void closingResponse(ClientRequestContext ctx, @Nullable Throwable cause) {
        requireNonNull(ctx, "ctx");
        final Consumer<Throwable> task = ctx.attr(CLOSING_RESPONSE_TASK);
        if (task != null) {
            task.accept(cause);
        }
    }

    private WebSocketClientUtil() {}
}
