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
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;

/**
 * Represents the result of parsing a single item from a JSON-RPC request (which could be part of a batch).
 * It holds either a successfully parsed {@link JsonRpcRequest} or a {@link JsonRpcResponse} representing
 * a parsing/validation error specific to that item.
 */
public final class JsonRpcItemParseResult {

    @Nullable
    private final JsonRpcRequest request;

    @Nullable
    private final JsonRpcResponse errorResponse;

    /**
     * Creates a new instance representing a successfully parsed request.
     */
    public JsonRpcItemParseResult(JsonRpcRequest request) {
        this.request = requireNonNull(request, "request");
        this.errorResponse = null;
    }

    /**
     * Creates a new instance representing a parsing or validation error.
     */
    public JsonRpcItemParseResult(JsonRpcResponse errorResponse) {
        requireNonNull(errorResponse, "errorResponse");
        // Check if the error object exists, as JsonRpcResponse always represents a response
        if (errorResponse.error() == null) {
            throw new IllegalArgumentException(
                    "errorResponse must contain an error object: " + errorResponse);
        }
        this.request = null;
        this.errorResponse = errorResponse;
    }

    /**
     * Returns {@code true} if this instance represents an error.
     */
    public boolean isError() {
        return errorResponse != null;
    }

    /**
     * Returns the successfully parsed {@link JsonRpcRequest}, or {@code null} if this represents an error.
     */
    @Nullable
    public JsonRpcRequest request() {
        return request;
    }

    /**
     * Returns the {@link JsonRpcResponse} representing an error, or {@code null} if this represents a
     * successfully parsed request.
     */
    @Nullable
    public JsonRpcResponse errorResponse() {
        return errorResponse;
    }
}
