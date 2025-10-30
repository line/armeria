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
package com.linecorp.armeria.common.jsonrpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A JSON-RPC response.
 */
@UnstableApi
public interface JsonRpcResponse {
    /**
    * Creates a new instance with result.
    */
    static JsonRpcResponse of(Object result) {
        requireNonNull(result, "result");
        checkArgument(!(result instanceof JsonRpcError),
            "result.class: %s (expected: not JsonRpcError)", result.getClass());

        return new SimpleJsonRpcResponse(result);
    }

    /**
    * Creates a new instance with error.
    */
    static JsonRpcResponse ofError(JsonRpcError error) {
        requireNonNull(error);
        return new SimpleJsonRpcResponse(error);
    }

    /**
     * Returns the JSON-RPC result.
     */
    @Nullable
    Object result();

    /**
    * Returns the JSON-RPC error.
    */
    @Nullable
    JsonRpcError error();
}
