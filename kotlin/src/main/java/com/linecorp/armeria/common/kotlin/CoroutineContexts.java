/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.kotlin;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.kotlin.CoroutineContextService;

import io.netty.util.AttributeKey;
import kotlin.coroutines.CoroutineContext;

/**
 * Configures a coroutine context for annotated services and Kotlin suspending functions.
 *
 * @see CoroutineContextService
 */
public final class CoroutineContexts {

    /**
     * {@link AnnotatedService} uses a coroutine context associated with this attribute
     * when calling suspending functions.
     */
    private static final AttributeKey<CoroutineContext> COROUTINE_CONTEXT_KEY =
            AttributeKey.valueOf(CoroutineContexts.class, "COROUTINE_CONTEXT_KEY");

    /**
     * Associates the given coroutine context with {@code COROUTINE_CONTEXT_KEY} attribute in the context.
     */
    public static void set(RequestContext ctx, CoroutineContext coroutineContext) {
        requireNonNull(ctx, "ctx");
        requireNonNull(coroutineContext, "coroutineContext");
        ctx.setAttr(COROUTINE_CONTEXT_KEY, coroutineContext);
    }

    /**
     * Returns the coroutine context mapped to {@code COROUTINE_CONTEXT_KEY} in the context.
     */
    @Nullable
    public static CoroutineContext get(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(COROUTINE_CONTEXT_KEY);
    }

    private CoroutineContexts() {}
}
