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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.internal.common.JacksonUtil;

final class JsonRpcMessageUtil {

    static final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();

    static JsonRpcVersion validateVersion(String version) {
        if (!JsonRpcVersion.JSON_RPC_2_0.getVersion().equals(version)) {
            throw new IllegalArgumentException("Unsupported JSON-RPC version: " + version);
        }
        return JsonRpcVersion.JSON_RPC_2_0;
    }

    static JsonRpcParameters validateParams(@Nullable Object params) {
        if (params == null) {
            return JsonRpcParameters.empty();
        }
        if (params instanceof List) {
            //noinspection unchecked
            return JsonRpcParameters.of((List<Object>) params);
        } else if (params instanceof Map) {
            //noinspection unchecked
            return JsonRpcParameters.of((Map<String, Object>) params);
        } else {
            throw new IllegalArgumentException("params must be either a List or a Map: " + params);
        }
    }

    static Object validateId(Object id) {
        requireNonNull(id, "JSON-RPC requests must include a non-null ID");

        if (id instanceof Integer || id instanceof Long || id instanceof String) {
            return id;
        }
        throw new IllegalArgumentException("ID must be either a Number or a String: " + id);
    }

    private JsonRpcMessageUtil() {}
}
