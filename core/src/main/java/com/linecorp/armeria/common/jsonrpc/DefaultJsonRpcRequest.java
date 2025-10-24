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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Default {@link JsonRpcRequest} implementation.
 */
final class DefaultJsonRpcRequest implements JsonRpcRequest {
    static final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();

    @Nullable
    private final Object id;
    private final String method;
    private final JsonRpcParameter params;
    private final JsonRpcVersion version;

    DefaultJsonRpcRequest(@Nullable Object id, String method, Iterable<?> params) {
        this(id, method, copyParams(params), JsonRpcVersion.JSON_RPC_2_0.getVersion());
    }

    DefaultJsonRpcRequest(@Nullable Object id, String method, Object... params) {
        this(id, method, copyParams(params), JsonRpcVersion.JSON_RPC_2_0.getVersion());
    }

    private DefaultJsonRpcRequest(
            @Nullable Object id,
            String method,
            Object params,
            String version) {
        checkArgument(JsonRpcVersion.JSON_RPC_2_0.getVersion().equals(version),
            "jsonrpc: %s (expected: 2.0)", version);
        checkArgument(id == null || id instanceof Number || id instanceof String,
            "id type: %s (expected: Null or Number or String)",
            Optional.ofNullable(id).map(Object::getClass).orElse(null));

        this.id = id;
        this.method = requireNonNull(method, "method");
        this.params = JsonRpcParameter.of(params);
        this.version = JsonRpcVersion.JSON_RPC_2_0;
    }

    @JsonCreator
    private static DefaultJsonRpcRequest fromJson(
            @JsonProperty("id") @Nullable Object id,
            @JsonProperty("method") String method,
            @JsonProperty("params") @Nullable Object params,
            @JsonProperty("jsonrpc") String version) {

        if (params == null) {
            return new DefaultJsonRpcRequest(id, method, ImmutableList.of());
        }

        if (params instanceof Iterable) {
            return new DefaultJsonRpcRequest(id, method, (Iterable<?>) params);
        }

        return new DefaultJsonRpcRequest(id, method, params, version);
    }

    private static List<Object> copyParams(Iterable<?> params) {
        requireNonNull(params, "params");
        if (params instanceof ImmutableList) {
            //noinspection unchecked
            return (List<Object>) params;
        }

        // Note we do not use ImmutableList.copyOf() here,
        // because it does not allow a null element and we should allow a null argument.
        final List<Object> copy;
        if (params instanceof Collection) {
            copy = new ArrayList<>(((Collection<?>) params).size());
        } else {
            copy = new ArrayList<>(8);
        }

        for (Object p : params) {
            copy.add(p);
        }

        return Collections.unmodifiableList(copy);
    }

    private static List<Object> copyParams(Object... params) {
        if (params.length == 0) {
            return ImmutableList.of();
        }

        final List<Object> copy = new ArrayList<>(params.length);
        Collections.addAll(copy, params);
        return Collections.unmodifiableList(copy);
    }

    @Override
    @JsonProperty
    public @Nullable Object id() {
        return id;
    }

    @Override
    @JsonProperty
    public String method() {
        return method;
    }

    @Override
    @JsonProperty
    public JsonRpcParameter params() {
        return params;
    }

    @Override
    @JsonProperty("jsonrpc")
    public JsonRpcVersion version() {
        return version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, method, params);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof JsonRpcRequest)) {
            return false;
        }

        final JsonRpcRequest that = (JsonRpcRequest) obj;
        return Objects.equals(id, that.id()) &&
               Objects.equals(method, that.method()) &&
               Objects.equals(params, that.params());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id())
                          .add("method", method())
                          .add("params", params())
                          .add("jsonrpc", version()).toString();
    }
}
