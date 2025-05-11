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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;

/**
 * Exception thrown when a JSON-RPC request cannot be parsed.
 */
public class JsonRpcRequestParseException extends RuntimeException {

    private static final long serialVersionUID = -5526383831125611610L;

    private final JsonRpcResponse errorResponse;
    @Nullable
    private final Object requestId;

    /**
     * Constructs a new instance.
     *
     * @param cause the cause of this exception.
     * @param errorResponse the {@link JsonRpcResponse} that represents the parse error.
     * @param requestId the ID of the request that could not be parsed, or {@code null} if the ID itself
     *                  could not be determined or if the request was a notification.
     */
    JsonRpcRequestParseException(Throwable cause, JsonRpcResponse errorResponse, @Nullable Object requestId) {
        super(cause.getMessage(), cause);
        this.errorResponse = errorResponse;
        this.requestId = requestId;
    }

    /**
     * Returns the {@link JsonRpcResponse} that represents the parse error.
     *
     * @return the {@link JsonRpcResponse} containing error details.
     */
    JsonRpcResponse getErrorResponse() {
        return errorResponse;
    }

    /**
     * Returns the ID of the request that failed to parse.
     *
     * @return the request ID, or {@code null} if the ID could not be determined during parsing
     *         or if the request was a notification.
     */
    @Nullable
    Object getRequestId() {
        return requestId;
    }
}
