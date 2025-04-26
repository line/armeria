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
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Represents a JSON-RPC request object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcRequest {

    private final String jsonrpc;
    private final String method;
    @Nullable
    private final JsonNode params;
    @Nullable
    private final Object id;

    /**
     * Creates a new instance.
     * This constructor is annotated with {@link JsonCreator} for Jackson deserialization.
     */
    @JsonCreator
    public JsonRpcRequest(@JsonProperty(value = "jsonrpc", required = true) String jsonrpc,
                          @JsonProperty(value = "method", required = true) String method,
                          @JsonProperty("params") @Nullable JsonNode params,
                          @JsonProperty("id") @Nullable Object id) {
        this.jsonrpc = jsonrpc;
        this.method = method;
        this.params = params;
        this.id = id;
    }

    /**
     * Returns the JSON-RPC protocol version.
     */
    @JsonProperty
    public String jsonrpc() {
        return jsonrpc;
    }

    /**
     * Returns the method name.
     */
    @JsonProperty
    public String method() {
        return method;
    }

    /**
     * Returns the parameters for the method, if any.
     */
    @JsonProperty
    @Nullable
    public JsonNode params() {
        return params;
    }

    /**
     * Returns the request ID, which may be a {@link String}, a {@link Number}, or {@code null}.
     */
    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }

    /**
     * Returns {@code true} if this request is a notification (i.e., the ID is null).
     */
    public boolean isNotification() {
        return id == null;
    }

    /**
     * Returns {@code true} if the parameters exist and are structured as an array.
     */
    public boolean hasArrayParams() {
        return params != null && params.isArray();
    }

    /**
     * Returns {@code true} if the parameters exist and are structured as an object.
     */
    public boolean hasObjectParams() {
        return params != null && params.isObject();
    }
}
