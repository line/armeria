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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The base for JsonRpcResponse.
 */
@UnstableApi
public abstract class AbstractJsonRpcResponse implements JsonRpcResponse {
    @Nullable
    private final Object result;

    @Nullable
    private final JsonRpcError error;

    /**
     * Creates a new instance with result.
     */
    protected AbstractJsonRpcResponse(Object result) {
        this.result = requireNonNull(result, "result");
        error = null;
    }

    /**
     * Creates a new instance with error.
     */
    protected AbstractJsonRpcResponse(JsonRpcError error) {
        result = null;
        this.error = requireNonNull(error, "error");
    }

    @Override
    @JsonProperty
    public final @Nullable Object result() {
        return result;
    }

    @Override
    @JsonProperty
    public final @Nullable JsonRpcError error() {
        return error;
    }
}
