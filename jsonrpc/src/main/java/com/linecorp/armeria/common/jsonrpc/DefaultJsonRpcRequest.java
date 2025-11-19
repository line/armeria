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

import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateId;
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateParams;
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateVersion;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Default {@link JsonRpcRequest} implementation.
 */
final class DefaultJsonRpcRequest extends AbstractJsonRpcRequest implements JsonRpcRequest {

    @JsonCreator
    private static DefaultJsonRpcRequest fromJson(
            @JsonProperty("id") Object id,
            @JsonProperty("method") String method,
            @JsonProperty("params") @Nullable Object params,
            @JsonProperty("jsonrpc") String version) {

        validateVersion(version);
        return new DefaultJsonRpcRequest(validateId(id), method, validateParams(params));
    }

    private final Object id;

    DefaultJsonRpcRequest(Object id, String method, JsonRpcParameters params) {
        super(method, params, JsonRpcVersion.JSON_RPC_2_0);
        validateId(id);
        this.id = id;
    }

    @Override
    @JsonProperty
    public Object id() {
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, super.hashCode());
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
        return Objects.equals(id, that.id()) && super.equals(that);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(JsonRpcResponse.class)
                          .add("id", id())
                          .add("method", method())
                          .add("params", params().isNamed() ? params().asMap() : params().asList())
                          .add("jsonrpc", version().getVersion())
                          .toString();
    }
}
