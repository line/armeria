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

import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Constructs a {@link JsonRpcService} to serve Json-RPC services.
 */
@UnstableApi
public class JsonRpcServiceBuilder {
    private final Map<String, JsonRpcHandler> methodHandlers = new HashMap<>();

    JsonRpcServiceBuilder() {}

    /**
    * Adds a Json-RPC {@link JsonRpcHandler} to this {@link JsonRpcServiceBuilder}.
    */
    public JsonRpcServiceBuilder addHandler(String methodName, JsonRpcHandler handler) {
        requireNonNull(methodName, "methodName");
        requireNonNull(handler, "handler");
        methodHandlers.put(methodName, handler);
        return this;
    }

    /**
     * Constructs a new {@link JsonRpcService}.
     */
    public JsonRpcService build() {
        return new JsonRpcService(methodHandlers);
    }
}
