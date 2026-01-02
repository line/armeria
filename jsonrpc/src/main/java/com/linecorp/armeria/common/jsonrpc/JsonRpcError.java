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

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A JSON-RPC 2.0 error object.
 */
@UnstableApi
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcError {

    /**
     * Invalid Request (-32600).
     * The JSON sent is not a valid Request object.
     */
    public static final JsonRpcError INVALID_REQUEST = new JsonRpcError(-32600, "Invalid Request");

    /**
     * Method not found (-32601).
     * The method does not exist / is not available.
     */
    public static final JsonRpcError METHOD_NOT_FOUND = new JsonRpcError(-32601, "Method not found");

    /**
     * Invalid params (-32602).
     * Invalid method parameter(s).
     */
    public static final JsonRpcError INVALID_PARAMS = new JsonRpcError(-32602, "Invalid params");

    /**
     * Internal error (-32603).
     * Internal JSON-RPC error.
     */
    public static final JsonRpcError INTERNAL_ERROR = new JsonRpcError(-32603, "Internal error");

    /**
     * Parse error (-32700).
     * Invalid JSON was received by the server.
     * An error occurred on the server while parsing the JSON text.
     */
    public static final JsonRpcError PARSE_ERROR = new JsonRpcError(-32700, "Parse error");

    private final int code;
    private final String message;
    @Nullable
    private final Object data;

    /**
     * Creates a new instance with the specified code, message, and optional data.
     */
    @JsonCreator
    public JsonRpcError(@JsonProperty("code") int code,
                        @JsonProperty("message") String message,
                        @JsonProperty("data") @Nullable Object data) {
        this.code = code;
        this.message = requireNonNull(message, "message");
        this.data = data;
    }

    /**
     * Creates a new instance with the specified code and message, and no additional data.
     */
    public JsonRpcError(int code, String message) {
        this(code, requireNonNull(message, "message"), null);
    }

    /**
     * Creates a new {@link JsonRpcError} instance with the same code and message as this instance.
     */
    public JsonRpcError withData(@Nullable Object data) {
        if (Objects.equals(data, this.data)) {
            return this;
        }

        return new JsonRpcError(code, message, data);
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
     * Returns the optional, application-defined data.
     */
    @JsonProperty
    @Nullable
    public Object data() {
        return data;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, data);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof JsonRpcError)) {
            return false;
        }

        final JsonRpcError that = (JsonRpcError) obj;
        return Objects.equals(code, that.code()) &&
               Objects.equals(message, that.message()) &&
               Objects.equals(data, that.data());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("code", code())
                          .add("message", message())
                          .add("data", data()).toString();
    }
}
