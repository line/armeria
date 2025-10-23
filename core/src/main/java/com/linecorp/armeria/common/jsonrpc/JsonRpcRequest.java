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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A Json-RPC request.
 */
@UnstableApi
public interface JsonRpcRequest {
    /**
    * Creates a new instance with no parameter.
    */
    static JsonRpcRequest of(@Nullable Object id, String method) {
        return new DefaultJsonRpcRequest(id, requireNonNull(method, "method"), ImmutableList.of());
    }

    /**
     * Creates a new instance with a single parameter.
     */
    static JsonRpcRequest of(@Nullable Object id, String method, @Nullable Object parameter) {
        final List<Object> parameters = parameter == null ? ImmutableList.of()
                                                          : ImmutableList.of(parameter);
        return new DefaultJsonRpcRequest(id, requireNonNull(method, "method"), parameters);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static JsonRpcRequest of(@Nullable Object id, String method, Iterable<?> params) {
        return new DefaultJsonRpcRequest(id,
                requireNonNull(method, "method"),
                requireNonNull(params, "params"));
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static JsonRpcRequest of(@Nullable Object id, String method, Object... params) {
        return new DefaultJsonRpcRequest(id,
                requireNonNull(method, "method"),
                requireNonNull(params, "params"));
    }

    /**
    * Creates a new instance with a JsonNode.
    */
    static JsonRpcRequest of(JsonNode node) throws JsonProcessingException {
        requireNonNull(node, "node");
        checkArgument(node.isObject(), "node.isObject(): %s (expected: true)", node.isObject());
        return JacksonUtil.newDefaultObjectMapper().treeToValue(node, DefaultJsonRpcRequest.class);
    }

    /**
     * Returns {@code true} if this request is a notification.
     */
    @JsonIgnore
    default boolean isNotification() {
        return id() == null;
    }

    /**
    * Returns the ID of the JSON-RPC request.
    * type must be Number or String
    */
    @Nullable
    Object id();

    /**
     * Returns the JSON-RPC method name.
     */
    String method();

    /**
     * Returns the parameters for the JSON-RPC method.
     */
    JsonRpcParameter params();

    /**
     * Returns the JSON-RPC version.
     */
    JsonRpcVersion version();
}
