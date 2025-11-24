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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

enum DefaultJsonRpcStatusFunction implements JsonRpcStatusFunction {

    INSTANCE;

    @Override
    public HttpStatus toHttpStatus(ServiceRequestContext ctx, JsonRpcRequest request, JsonRpcResponse response,
                                   JsonRpcError error) {
        final int code = error.code();
        if (code == JsonRpcError.INVALID_REQUEST.code()) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == JsonRpcError.METHOD_NOT_FOUND.code()) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == JsonRpcError.INVALID_PARAMS.code()) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == JsonRpcError.PARSE_ERROR.code()) {
            return HttpStatus.BAD_REQUEST;
        }

        if (code == JsonRpcError.INTERNAL_ERROR.code()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // Reserved for implementation-defined server-errors.
        // https://www.jsonrpc.org/specification#error_object
        //noinspection IfStatementWithIdenticalBranches
        if (code >= -32099 && code <= -32000) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // Default to 500 Internal Server Error for all other cases.
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
