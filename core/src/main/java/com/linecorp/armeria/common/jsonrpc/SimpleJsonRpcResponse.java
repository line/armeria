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

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class SimpleJsonRpcResponse extends AbstractJsonRpcResponse {
    SimpleJsonRpcResponse(Object result) {
        super(result);
    }

    SimpleJsonRpcResponse(JsonRpcError error) {
        super(error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result(), error());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof SimpleJsonRpcResponse)) {
            return false;
        }

        final SimpleJsonRpcResponse that = (SimpleJsonRpcResponse) obj;
        return Objects.equals(result(), that.result()) &&
               Objects.equals(error(), that.error());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("result", result())
                          .add("error", error()).toString();
    }
}
