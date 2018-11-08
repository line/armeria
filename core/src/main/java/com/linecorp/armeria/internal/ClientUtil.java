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
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;

public final class ClientUtil {

    public static <I extends Request, O extends Response, U extends Client<I, O>> O executeWithFallback(
            U delegate, ClientRequestContext ctx, I req,
            BiFunction<ClientRequestContext, Throwable, O> fallback) {
        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        requireNonNull(fallback, "fallback");

        try (SafeCloseable ignored = ctx.push()) {
            return delegate.execute(ctx, req);
        } catch (Throwable cause) {
            final O fallbackRes = fallback.apply(ctx, cause);
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_START)) {
                // An exception is raised even before sending a request, so end the request with the exception.
                logBuilder.endRequest(cause);
            }
            logBuilder.endResponse(cause);
            return fallbackRes;
        }
    }

    private ClientUtil() {}
}
