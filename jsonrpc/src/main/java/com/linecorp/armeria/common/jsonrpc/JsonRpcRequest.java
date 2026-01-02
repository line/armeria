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

import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.objectMapper;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.jsonrpc.JsonRpcParseException;

/**
 * A JSON-RPC request.
 */
@UnstableApi
public interface JsonRpcRequest extends JsonRpcMessage, JsonRpcMethodInvokable {

    /**
     * Creates a new instance with no parameters.
     * Note that the {@code id} can be of type {@link String}, {@link Long} or {@link Integer}.
     */
    static JsonRpcRequest of(Object id, String method) {
        return of(id, method, JsonRpcParameters.empty());
    }

    /**
     * Creates a new instance with the specified {@link JsonRpcParameters}.
     * Note that the {@code id} can be of type {@link String}, {@link Long} or {@link Integer}.
     */
    static JsonRpcRequest of(Object id, String method, JsonRpcParameters parameters) {
        return new DefaultJsonRpcRequest(id, method, parameters);
    }

    /**
     * Creates a new instance with the specified parameters.
     * Note that the {@code id} can be of type {@link String}, {@link Long} or {@link Integer}.
     */
    static JsonRpcRequest of(Object id, String method, Iterable<?> parameters) {
        return of(id, method, JsonRpcParameters.of(parameters));
    }

    /**
     * Creates a new instance with the specified parameters.
     * Note that the {@code id} can be of type {@link String}, {@link Long} or {@link Integer}.
     */
    static JsonRpcRequest of(Object id, String method, Map<String, ?> parameters) {
        return of(id, method, JsonRpcParameters.of(parameters));
    }

    /**
     * Parses the specified {@link JsonNode} into a {@link JsonRpcRequest}.
     */
    @JsonCreator
    static JsonRpcRequest fromJson(JsonNode node) {
        requireNonNull(node, "node");
        try {
            return objectMapper.treeToValue(node, DefaultJsonRpcRequest.class);
        } catch (JsonProcessingException e) {
            throw new JsonRpcParseException(e);
        }
    }

    /**
     * Parses the specified JSON string into a {@link JsonRpcRequest}.
     */
    static JsonRpcRequest fromJson(String json) {
        requireNonNull(json, "json");
        try {
            return objectMapper.readValue(json, DefaultJsonRpcRequest.class);
        } catch (JsonProcessingException e) {
            throw new JsonRpcParseException(e);
        }
    }

    /**
     * Returns the ID of the JSON-RPC request.
     * The type must be {@link String}, {@link Long}, {@link Integer}.
     */
    @JsonProperty
    Object id();
}
