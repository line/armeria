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
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateId;
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateVersion;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
final class DefaultJsonRpcResponse implements JsonRpcResponse {
    @Nullable
    private final Object id;
    @Nullable
    private final Object result;
    @Nullable
    private final JsonRpcError error;
    private final JsonRpcVersion version = JsonRpcVersion.JSON_RPC_2_0;

    DefaultJsonRpcResponse(@Nullable Object id,
                           @Nullable Object result,
                           @Nullable JsonRpcError error) {
        checkArgument((result != null && error == null) || (result == null && error != null),
                      "Either result or error must be set, but not both. " +
                      "result: %s, error: %s", result, error);
        if (id != null) {
            validateId(id);
        }
        if (result != null) {
            checkArgument(!(result instanceof JsonRpcError),
                          "result.class: %s (expected: not JsonRpcError)", result.getClass());
        }
        this.id = id;
        this.result = result;
        this.error = error;
    }

    @JsonCreator
    DefaultJsonRpcResponse(@JsonProperty("id") @Nullable Object id,
                           @JsonProperty("result") @Nullable Object result,
                           @JsonProperty("error") @Nullable JsonRpcError error,
                           @JsonProperty("jsonrpc") String version) {
        this(id, result, error);
        validateVersion(version);
    }

    @Override
    @JsonProperty
    public @Nullable Object id() {
        return id;
    }

    @Override
    public JsonRpcResponse withId(Object id) {
        if (Objects.equals(this.id, id)) {
            return this;
        }
        validateId(id);
        return new DefaultJsonRpcResponse(id, result, error);
    }

    @Override
    public @Nullable Object result() {
        return result;
    }

    @Override
    public @Nullable JsonRpcError error() {
        return error;
    }

    @Override
    public JsonRpcVersion version() {
        return version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id(), result(), error(), version());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof JsonRpcResponse)) {
            return false;
        }

        final JsonRpcResponse that = (JsonRpcResponse) obj;
        return Objects.equals(id, that.id()) &&
               Objects.equals(result, that.result()) &&
               Objects.equals(error, that.error()) &&
               version == that.version();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(JsonRpcResponse.class)
                          .omitNullValues()
                          .add("id", id())
                          .add("result", result())
                          .add("error", error())
                          .add("version", version().getVersion()).toString();
    }
}
