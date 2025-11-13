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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

enum DefaultJsonRpcExceptionHandler implements JsonRpcExceptionHandler {

    INSTANCE;

    @Override
    public JsonRpcResponse handleException(ServiceRequestContext ctx, @Nullable JsonRpcMessage input,
                                           Throwable cause) {
        if (input == null) {
            return JsonRpcResponse.ofFailure(JsonRpcError.PARSE_ERROR.withData(cause.getMessage()));
        }

        if (cause instanceof JsonRpcParseException) {
            return JsonRpcResponse.ofFailure(JsonRpcError.PARSE_ERROR.withData(cause.getMessage()));
        }

        if (cause instanceof IllegalArgumentException) {
            return JsonRpcResponse.ofFailure(JsonRpcError.INVALID_REQUEST.withData(cause.getMessage()));
        }

        return JsonRpcResponse.ofFailure(JsonRpcError.INTERNAL_ERROR.withData(cause.getMessage()));
    }

    private static HttpResponse renderError(ServiceRequestContext ctx, HttpStatus status,
                                            @Nullable Object id,
                                            JsonRpcError error, Throwable cause) {
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofFailure(error.withData(cause.getMessage()));
        ctx.logBuilder().responseContent(rpcResponse, rpcResponse);
        return HttpResponse.ofJson(status, rpcResponse);
    }
}
