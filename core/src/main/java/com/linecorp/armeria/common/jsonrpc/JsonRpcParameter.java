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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A wrapper for
 * <a href="https://www.jsonrpc.org/specification#parameter_structures">JSON-RPC 2.0 parameters</a>.
 * The parameters can be either positional (a {@link List}) or named (a {@link Map}).
 */
@UnstableApi
public final class JsonRpcParameter {
    @Nullable
    private final List<Object> positional;

    @Nullable
    private final Map<String, Object> named;

    private JsonRpcParameter(List<Object> positional) {
        this.positional = positional;
        this.named = null;
    }

    private JsonRpcParameter(Map<String, Object> named) {
        this.positional = null;
        this.named = named;
    }

    /**
     * Creates a new {@link JsonRpcParameter} instance from positional parameters.
     */
    public static JsonRpcParameter of(List<Object> positionalParams) {
        return new JsonRpcParameter(positionalParams);
    }

    /**
     * Creates a new {@link JsonRpcParameter} instance from named parameters.
     */
    public static JsonRpcParameter of(Map<String, Object> namedParams) {
        return new JsonRpcParameter(namedParams);
    }

    /**
     * Returns {@code true} if this parameter is a positional.
     */
    public boolean isPositional() {
        return positional != null;
    }

    /**
     * Returns {@code true} if this parameter is a named.
     */
    public boolean isNamed() {
        return named != null;
    }

    /**
     * Returns the parameters as a {@link List}.
     */
    public List<Object> asList() {
        if (positional == null) {
            throw new IllegalStateException("Not positional parameters.");
        }
        return positional;
    }

    /**
     * Returns the parameters as a {@link Map}.
     */
    public Map<String, Object> asMap() {
        if (named == null) {
            throw new IllegalStateException("Not named parameters.");
        }
        return named;
    }

    /**
     * Jackson uses this method to serialize the object.
     * It returns the non-null parameter (either positional List or named Map).
     */
    @JsonValue
    private Object value() {
        if (positional != null) {
            return positional;
        } else {
            return Objects.requireNonNull(named);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(positional, named);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof JsonRpcParameter)) {
            return false;
        }

        final JsonRpcParameter that = (JsonRpcParameter) obj;
        return Objects.equals(positional, that.positional) &&
                Objects.equals(named, that.named);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("positional", positional)
                .add("named", named).toString();
    }
}
