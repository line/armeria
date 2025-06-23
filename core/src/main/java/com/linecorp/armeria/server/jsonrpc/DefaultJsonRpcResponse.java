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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.AbstractJsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcConstants;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;

@UnstableApi
@JsonInclude(JsonInclude.Include.NON_NULL)
final class DefaultJsonRpcResponse extends AbstractJsonRpcResponse {
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
    public String version() {
        return JsonRpcConstants.JSON_RPC_VERSION;
    }
}
