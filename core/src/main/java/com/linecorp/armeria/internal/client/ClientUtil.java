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
package com.linecorp.armeria.internal.client;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.SafeCloseable;

public final class ClientUtil {

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O initContextAndExecuteWithFallback(
            U delegate,
            DefaultClientRequestContext ctx,
            EndpointGroup endpointGroup,
            BiFunction<ClientRequestContext, Throwable, O> fallbackResponseFactory) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(fallbackResponseFactory, "fallbackResponseFactory");

        try {
            endpointGroup = mapEndpoint(ctx, endpointGroup);
            if (ctx.init(endpointGroup)) {
                return pushAndExecute(delegate, ctx);
            } else {
                // Context initialization has failed, which means:
                // - ctx.log() has been completed with an exception.
                // - ctx.request() has been aborted (if not null).
                // - the decorator chain was not invoked at all.
                // See `init()` and `failEarly()` in `DefaultClientRequestContext`.

                // Call the decorator chain anyway so that the request is seen by the decorators.
                final O res = pushAndExecute(delegate, ctx);

                // We will use the fallback response which is created from the exception
                // raised in ctx.init(), so the response returned can be aborted.
                if (res instanceof StreamMessage) {
                    ((StreamMessage<?>) res).abort();
                }

                // No need to call `fail()` because failed by `DefaultRequestContext.init()` already.
                final Throwable cause = ctx.log().partial().requestCause();
                assert cause != null;
                return fallbackResponseFactory.apply(ctx, cause);
            }
        } catch (Throwable cause) {
            fail(ctx, cause);
            return fallbackResponseFactory.apply(ctx, cause);
        }
    }

    private static EndpointGroup mapEndpoint(ClientRequestContext ctx, EndpointGroup endpointGroup) {
        if (endpointGroup instanceof Endpoint) {
            return requireNonNull(ctx.options().endpointRemapper().apply((Endpoint) endpointGroup),
                                  "endpointRemapper returned null.");
        } else {
            return endpointGroup;
        }
    }

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O executeWithFallback(U delegate, ClientRequestContext ctx,
                          BiFunction<ClientRequestContext, Throwable, O> fallbackResponseFactory) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(fallbackResponseFactory, "fallbackResponseFactory");

        try {
            return pushAndExecute(delegate, ctx);
        } catch (Throwable cause) {
            fail(ctx, cause);
            return fallbackResponseFactory.apply(ctx, cause);
        }
    }

    private static <I extends Request, O extends Response, U extends Client<I, O>>
    O pushAndExecute(U delegate, ClientRequestContext ctx) throws Exception {
        @SuppressWarnings("unchecked")
        final I req = (I) firstNonNull(ctx.request(), ctx.rpcRequest());
        try (SafeCloseable ignored = ctx.push()) {
            return delegate.execute(ctx, req);
        }
    }

    private static void fail(ClientRequestContext ctx, Throwable cause) {
        final HttpRequest req = ctx.request();
        if (req != null) {
            req.abort(cause);
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest(cause);
        logBuilder.endResponse(cause);
    }

    private ClientUtil() {}
}
