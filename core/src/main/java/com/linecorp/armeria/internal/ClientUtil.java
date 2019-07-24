/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.SafeCloseable;

public final class ClientUtil {

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O initContextAndExecuteWithFallback(U delegate, DefaultClientRequestContext ctx, Endpoint endpoint,
                                        BiFunction<ClientRequestContext, Throwable, O> fallback) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(endpoint, "endpoint");
        requireNonNull(fallback, "fallback");

        try {
            if (ctx.init(endpoint)) {
                return pushAndExecute(delegate, ctx);
            } else {
                // Context initialization has failed, but we call the decorator chain anyway
                // so that the request is seen by the decorators.
                final O res = pushAndExecute(delegate, ctx);
                // We will use the fallback response which is created from the exception
                // raised in ctx.init(), so the response returned can be aborted.
                if (res instanceof StreamMessage) {
                    ((StreamMessage<?>) res).abort();
                }
                return fallback.apply(ctx, ctx.log().requestCause());
            }
        } catch (Throwable cause) {
            return failAndGetFallbackResponse(ctx, fallback, cause);
        }
    }

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O executeWithFallback(U delegate, ClientRequestContext ctx,
                          BiFunction<ClientRequestContext, Throwable, O> fallback) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(fallback, "fallback");

        try {
            return pushAndExecute(delegate, ctx);
        } catch (Throwable cause) {
            return failAndGetFallbackResponse(ctx, fallback, cause);
        }
    }

    private static <I extends Request, O extends Response, U extends Client<I, O>>
    O pushAndExecute(U delegate, ClientRequestContext ctx) throws Exception {
        try (SafeCloseable ignored = ctx.push()) {
            return delegate.execute(ctx, ctx.request());
        }
    }

    private static <O extends Response> O failAndGetFallbackResponse(
            ClientRequestContext ctx,
            BiFunction<ClientRequestContext, Throwable, O> fallback,
            Throwable cause) {

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_START)) {
            // An exception is raised even before sending a request,
            // so end the request with the exception.
            logBuilder.endRequest(cause);

            final Request req = ctx.request();
            if (req instanceof StreamMessage) {
                ((StreamMessage<?>) req).abort();
            }
        }
        logBuilder.endResponse(cause);

        return fallback.apply(ctx, cause);
    }

    private ClientUtil() {}
}
