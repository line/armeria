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

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A wrapper for
 * <a href="https://www.jsonrpc.org/specification#parameter_structures">JSON-RPC 2.0 parameters</a>.
 * The parameters can be either positional (a {@link List}) or named (a {@link Map}).
 */
public final class JsonRpcParameter {
    @JsonValue
    private final Object value;

    private JsonRpcParameter(Object value) {
        this.value = value;
    }

    /**
     * Creates a new {@link JsonRpcParameter} instance from the parsed parameter object.
     */
    public static JsonRpcParameter of(Object parsedParams) {
        checkArgument(parsedParams instanceof List || parsedParams instanceof Map,
                "params type: %s (expected: List or Map)",
                parsedParams != null ? parsedParams.getClass().getName() : "null");
        return new JsonRpcParameter(parsedParams);
    }

    /**
    * Returns {@code true} if this Parameter is a Positional.
    */
    public boolean isPositional() {
        return value instanceof List;
    }

    /**
    * Returns {@code true} if this Parameter is a Named.
    */
    public boolean isNamed() {
        return value instanceof Map;
    }

    /**
     * Returns the parameters as a {@link List}.
     */
    @SuppressWarnings("unchecked")
    public List<Object> asList() {
        if (!isPositional()) {
            throw new IllegalStateException("Not positional parameters.");
        }
        return (List<Object>) value;
    }

    /**
     * Returns the parameters as a {@link Map}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        if (!isNamed()) {
            throw new IllegalStateException("Not named parameters.");
        }
        return (Map<String, Object>) value;
    }
}
