/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.jsonrpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An exception handler for JSON-RPC services.
 */
@UnstableApi
@FunctionalInterface
public interface JsonRpcExceptionHandler {

    /**
     * Returns a default {@link JsonRpcExceptionHandler} instance.
     */
    static JsonRpcExceptionHandler of() {
        return DefaultJsonRpcExceptionHandler.INSTANCE;
    }

    /**
     * Handles the given exception and returns an {@link JsonRpcResponse}.
     * @param ctx the {@link ServiceRequestContext}
     * @param input the parsed {@link JsonRpcMessage}, or {@code null} if the request could not be parsed.
     * @param cause the exception that occurred while processing the request.
     */
    @Nullable
    JsonRpcResponse handleException(ServiceRequestContext ctx, @Nullable JsonRpcMessage input,
                                    Throwable cause);

    /**
     * Returns a composed {@link JsonRpcExceptionHandler} that first applies this handler, and if it
     * returns {@code null}, applies the {@code next} handler.
     */
    default JsonRpcExceptionHandler orElse(JsonRpcExceptionHandler next) {
        requireNonNull(next, "next");
        return (ctx, input, cause) -> {
            final JsonRpcResponse response = handleException(ctx, input, cause);
            if (response != null) {
                return response;
            }
            return next.handleException(ctx, input, cause);
        };
    }
}
