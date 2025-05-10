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
 * Represents a JSON-RPC response object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcResponse {

    private static final String JSONRPC_VERSION = "2.0";

    private final String jsonrpc;

    @Nullable
    private final Object result;

    @Nullable
    private final JsonRpcError error;

    @Nullable
    private final Object id;

    private JsonRpcResponse(@Nullable Object result, @Nullable Object id) {
        this.jsonrpc = JSONRPC_VERSION; // Fixed value for JSON-RPC version
        this.result = result;
        this.error = null;
        this.id = id;
    }

    private JsonRpcResponse(JsonRpcError error, @Nullable Object id) {
        this.jsonrpc = JSONRPC_VERSION; // Fixed value for JSON-RPC version
        this.result = null;
        this.error = error;
        this.id = id;
    }

    /**
     * Creates a new instance.
     * This constructor is annotated with {@link JsonCreator} for Jackson deserialization.
     */
    @JsonCreator
    public JsonRpcResponse(@JsonProperty("jsonrpc") String jsonrpc,
                           @JsonProperty("result") @Nullable Object result,
                           @JsonProperty("error") @Nullable JsonRpcError error,
                           @JsonProperty("id") @Nullable Object id) {
        this.jsonrpc = jsonrpc;
        this.result = result;
        this.error = error;
        this.id = id;
    }

    /**
     * Creates a successful JSON-RPC response.
     *
     * @param result the result object
     * @param id the request ID
     */
    public static JsonRpcResponse ofSuccess(@Nullable Object result, @Nullable Object id) {
        return new JsonRpcResponse(result, id);
    }

    /**
     * Creates an error JSON-RPC response.
     *
     * @param error the error object
     * @param id the request ID
     */
    public static JsonRpcResponse ofError(JsonRpcError error, @Nullable Object id) {
        return new JsonRpcResponse(error, id);
    }

    /**
     * Returns the JSON-RPC protocol version.
     */
    @JsonProperty("jsonrpc")
    public String jsonRpcVersion() {
        return jsonrpc;
    }

    /**
     * Returns the result of the method invocation, or {@code null} if there was an error.
     */
    @JsonProperty
    @Nullable
    public Object result() {
        return result;
    }

    /**
     * Returns the error object if an error occurred, or {@code null} otherwise.
     */
    @JsonProperty
    @Nullable
    public JsonRpcError error() {
        return error;
    }

    /**
     * Returns the request ID. This should be the same ID as the request it is responding to.
     * It should be {@code null} if there was an error detecting the request ID
     * (e.g. Parse error/Invalid Request).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS) // Ensure 'id' is always present, even if null
    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }
}
