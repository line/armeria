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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A function that maps a {@link JsonRpcError} to an {@link HttpStatus}.
 */
@UnstableApi
@FunctionalInterface
public interface JsonRpcStatusFunction {

    /**
     * Returns a default {@link JsonRpcStatusFunction}.
     */
    static JsonRpcStatusFunction of() {
        return DefaultJsonRpcStatusFunction.INSTANCE;
    }

    /**
     * Maps the specified {@link JsonRpcError} to an {@link HttpStatus}.
     */
    @Nullable
    HttpStatus toHttpStatus(ServiceRequestContext ctx, JsonRpcRequest request, JsonRpcResponse response,
                            JsonRpcError error);

    /**
     * Returns a composed {@link JsonRpcStatusFunction} that tries this function first, and if it returns
     * {@code null}, tries the {@code next} function.
     */
    default JsonRpcStatusFunction orElse(JsonRpcStatusFunction next) {
        requireNonNull(next, "next");
        return (ctx, request, response, error) -> {
            final HttpStatus status = toHttpStatus(ctx, request, response, error);
            if (status != null) {
                return status;
            }
            return next.toHttpStatus(ctx, request, response, error);
        };
    }
}
