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

import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateParams;
import static com.linecorp.armeria.common.jsonrpc.JsonRpcMessageUtil.validateVersion;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

final class DefaultJsonRpcNotification extends AbstractJsonRpcRequest implements JsonRpcNotification {

    DefaultJsonRpcNotification(String method, JsonRpcParameters params) {
        super(method, params, JsonRpcVersion.JSON_RPC_2_0);
    }

    @JsonCreator
    DefaultJsonRpcNotification(@JsonProperty("method") String method,
                               @JsonProperty("params") Object params,
                               @JsonProperty("jsonrpc") String version) {
        super(method, validateParams(params), validateVersion(version));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JsonRpcNotification)) {
            return false;
        }
        final JsonRpcNotification that = (JsonRpcNotification) o;
        return method().equals(that.method()) &&
               params().equals(that.params()) &&
               version() == that.version();
    }

    @Override
    public int hashCode() {
        return Objects.hash(method(), params(), version());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(JsonRpcNotification.class)
                          .add("method", method())
                          .add("params", params().isNamed() ? params().asMap() : params().asList())
                          .add("jsonrpc", version().getVersion())
                          .toString();
    }
}
