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
package com.linecorp.armeria.server.jsonrpc;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.AbstractJsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcVersion;

@JsonInclude(JsonInclude.Include.NON_NULL)
final class DefaultJsonRpcResponse extends AbstractJsonRpcResponse {
    @Nullable
    private final Object id;

    DefaultJsonRpcResponse(Object id, Object result) {
        super(result);
        this.id = requireNonNull(id, "id");
    }

    DefaultJsonRpcResponse(@Nullable Object id, JsonRpcError error) {
        super(error);
        this.id = id;
    }

    @JsonProperty
    public @Nullable Object id() {
        return id;
    }

    @JsonProperty("jsonrpc")
    public JsonRpcVersion version() {
        return JsonRpcVersion.JSON_RPC_2_0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id(), result(), error());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof DefaultJsonRpcResponse)) {
            return false;
        }

        final DefaultJsonRpcResponse that = (DefaultJsonRpcResponse) obj;
        return Objects.equals(id(), that.id()) &&
               Objects.equals(result(), that.result()) &&
               Objects.equals(error(), that.error());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id())
                          .add("result", result())
                          .add("error", error()).toString();
    }
}
