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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Represents a JSON-RPC error object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcError {

    private final int code;

    private final String message;

    @Nullable
    private final Object data;

    /**
     * Creates a new instance with the specified {@link JsonRpcErrorCode} and data.
     */
    public JsonRpcError(JsonRpcErrorCode code, @Nullable Object data) {
        this(code.code(), code.message(), data);
    }

    /**
     * Creates a new instance with the specified code, message, and data.
     * This constructor is annotated with {@link JsonCreator} for Jackson deserialization.
     */
    @JsonCreator
    public JsonRpcError(@JsonProperty(value = "code", required = true) int code,
                        @JsonProperty(value = "message", required = true) String message,
                        @JsonProperty("data") @Nullable Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * Creates a new instance with the specified code and message.
     */
    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    /**
     * Returns the error code.
     */
    @JsonProperty
    public int code() {
        return code;
    }

    /**
     * Returns the error message.
     */
    @JsonProperty
    public String message() {
        return message;
    }

    /**
     * Returns the optional data associated with the error.
     */
    @JsonProperty
    @Nullable
    public Object data() {
        return data;
    }

    /**
     * Creates a 'Parse error' {@link JsonRpcError}.
     */
    public static JsonRpcError parseError(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.PARSE_ERROR, data);
    }

    /**
     * Creates an 'Invalid Request' {@link JsonRpcError}.
     */
    public static JsonRpcError invalidRequest(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_REQUEST, data);
    }

    /**
     * Creates a 'Method not found' {@link JsonRpcError}.
     */
    public static JsonRpcError methodNotFound(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND, data);
    }

    /**
     * Creates an 'Invalid params' {@link JsonRpcError}.
     */
    public static JsonRpcError invalidParams(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, data);
    }

    /**
     * Creates an 'Internal error' {@link JsonRpcError}.
     */
    public static JsonRpcError internalError(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, data);
    }
}
