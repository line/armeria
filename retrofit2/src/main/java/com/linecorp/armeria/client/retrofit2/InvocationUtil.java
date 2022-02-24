/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;

import io.netty.util.AttributeKey;
import retrofit2.Invocation;

/**
 * Retrieves a Retrofit {@link Invocation} associated with a {@link RequestLog}.
 */
public final class InvocationUtil {

    private static final AttributeKey<Invocation> RETROFIT_INVOCATION =
            AttributeKey.valueOf(InvocationUtil.class, "RETROFIT_INVOCATION");

    /**
     * Retrieves a Retrofit {@link Invocation} associated with a {@link RequestLog}.
     */
    @Nullable
    public static Invocation getInvocation(RequestLogAccess log) {
        return log.context().attr(RETROFIT_INVOCATION);
    }

    /**
     * Associates the specified {@link Invocation} with the specified {@link ClientRequestContext} and sets
     * the {@link Invocation}'s method name to {@link RequestLogProperty#NAME}.
     */
    static void setInvocation(ClientRequestContext ctx, @Nullable Invocation invocation) {
        if (invocation == null) {
            return;
        }
        ctx.setAttr(RETROFIT_INVOCATION, invocation);
        ctx.logBuilder().name(invocation.method().getDeclaringClass().getName(), invocation.method().getName());
    }

    private InvocationUtil() {}
}
