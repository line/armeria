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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

@UnstableApi
@FunctionalInterface
public interface ServiceErrorHandler {
    /**
     * Returns an {@link HttpResponse} for the given {@link Throwable} raised by a service.
     * This method is invoked once for each request failed with a service-level exception.
     *
     * @param ctx the {@link ServiceRequestContext} of the current request.
     * @param cause the {@link Throwable} raised by the service that handled the current request.
     *
     * @return an {@link HttpResponse} to send to the client, or {@code null} to let the next handler
     *         specified with {@link #orElse(ServiceErrorHandler)} handle the event.
     */
    @Nullable
    HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause);

    /**
     * Returns a newly created {@link ServiceErrorHandler} that tries this {@link ServiceErrorHandler} first and
     * then the specified {@link ServiceErrorHandler} when the first call returns {@code null}.
     *
     * <pre>{@code
     * ServiceErrorHandler handler = (ctx, cause) -> {
     *     if (cause instanceof FirstException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * }
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) == null;
     * assert handler.onServiceException(ctx, new ThirdException()) == null;
     *
     * ServiceErrorHandler combinedHandler = handler.orElse((ctx, cause) -> {
     *     if (cause instanceof SecondException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * });
     *
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) != null;
     * assert handler.onServiceException(ctx, new ThirdException()) == null;
     *
     * // The default handler never returns null.
     * ServiceErrorHandler nonNullHandler = combinedHandler.orElse(ServiceErrorHandler.ofDefault());x
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) != null;
     * assert handler.onServiceException(ctx, new ThirdException()) != null;
     * }</pre>
     */
    default ServiceErrorHandler orElse(ServiceErrorHandler other) {
        requireNonNull(other, "other");
        return (ctx, cause) -> {
            final HttpResponse response = onServiceException(ctx, cause);
            if (response != null) {
                return response;
            }
            return other.onServiceException(ctx, cause);
        };
    }
}
