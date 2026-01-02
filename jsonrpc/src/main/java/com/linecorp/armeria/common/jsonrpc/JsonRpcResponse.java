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
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.objectMapper;
import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.jsonrpc.JsonRpcParseException;
import com.linecorp.armeria.server.jsonrpc.JsonRpcService;

/**
 * A JSON-RPC response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@UnstableApi
public interface JsonRpcResponse extends JsonRpcMessage {

    /**
     * Creates a new instance with the specified {@code id} and {@code result}.
     * Note that the {@code id} can be of type {@link String}, {@link Long}, {@link Integer}.
     */
    static JsonRpcResponse ofSuccess(Object id, Object result) {
        requireNonNull(id, "id");
        requireNonNull(result, "result");
        checkArgument(!(result instanceof JsonRpcError),
                      "result.class: %s (expected: not JsonRpcError)", result.getClass());
        return new DefaultJsonRpcResponse(id, result, null);
    }

    /**
     * Creates a new instance with {@code result}.
     * The {@code id} field is automatically filled if this response is sent via {@link JsonRpcService}.
     */
    static JsonRpcResponse ofSuccess(Object result) {
        requireNonNull(result, "result");
        return new DefaultJsonRpcResponse(null, result, null);
    }

    /**
     * Creates a new instance with the specified {@code id} and {@code result}.
     * Note that the {@code id} can be of type {@link String}, {@link Long}, {@link Integer}.
     */
    static JsonRpcResponse ofFailure(Object id, JsonRpcError error) {
        requireNonNull(id, "id");
        requireNonNull(error, "error");
        return new DefaultJsonRpcResponse(id, null, error);
    }

    /**
     * Creates a new instance with the specified {@link JsonRpcError}.
     * The {@code id} field is automatically filled if this response is sent via {@link JsonRpcService}.
     */
    static JsonRpcResponse ofFailure(JsonRpcError error) {
        requireNonNull(error);
        return new DefaultJsonRpcResponse(null, null, error);
    }

    /**
     * Parses the specified {@link JsonNode} into a {@link JsonRpcResponse}.
     */
    @JsonCreator
    static JsonRpcResponse fromJson(JsonNode node) {
        requireNonNull(node, "node");
        try {
            return objectMapper.treeToValue(node, DefaultJsonRpcResponse.class);
        } catch (JsonProcessingException e) {
            throw new JsonRpcParseException(e);
        }
    }

    /**
     * Parses the specified JSON string into a {@link JsonRpcResponse}.
     */
    static JsonRpcResponse fromJson(String json) {
        requireNonNull(json, "json");
        try {
            return objectMapper.readValue(json, DefaultJsonRpcResponse.class);
        } catch (JsonProcessingException e) {
            throw new JsonRpcParseException(e);
        }
    }

    /**
     * Returns a new {@link JsonRpcStreamableResponse} to stream multiple JSON-RPC messages.
     */
    static JsonRpcStreamableResponse streaming() {
        return new DefaultJsonRpcStreamableResponse();
    }

    /**
     * Returns the JSON-RPC id.
     */
    @Nullable
    @JsonProperty
    Object id();

    /**
     * Returns a new {@link JsonRpcResponse} with the specified {@code id}.
     * If the current {@link #id()} is equal to the specified {@code id}, this instance is returned.
     */
    JsonRpcResponse withId(Object id);

    /**
     * Returns the JSON-RPC result.
     */
    @Nullable
    @JsonProperty
    Object result();

    /**
     * Returns {@code true} if this response has a result.
     */
    @JsonIgnore
    default boolean hasResult() {
        return result() != null;
    }

    /**
     * Returns {@code true} if this response indicates a successful response.
     */
    @JsonIgnore
    default boolean isSuccess() {
        return hasResult();
    }

    /**
     * Returns the JSON-RPC error.
     */
    @Nullable
    @JsonProperty
    JsonRpcError error();

    /**
     * Returns {@code true} if this response has an error.
     */
    @JsonIgnore
    default boolean hasError() {
        return error() != null;
    }
}
