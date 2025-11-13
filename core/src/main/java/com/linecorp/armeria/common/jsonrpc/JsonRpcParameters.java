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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A wrapper for
 * <a href="https://www.jsonrpc.org/specification#parameter_structures">JSON-RPC 2.0 parameters</a>.
 * The parameters can be either positional (a {@link List}) or named (a {@link Map}).
 */
@UnstableApi
public final class JsonRpcParameters {

    private static final JsonRpcParameters EMPTY = new JsonRpcParameters(ImmutableList.of());

    /**
     * Returns an empty {@link JsonRpcParameters} instance.
     */
    public static JsonRpcParameters empty() {
        return EMPTY;
    }

    /**
     * Creates a new {@link JsonRpcParameters} instance from positional parameters.
     */
    public static JsonRpcParameters of(Iterable<?> positionalParams) {
        requireNonNull(positionalParams, "positionalParams");
        return new JsonRpcParameters(copyParams(positionalParams));
    }

    /**
     * Creates a new {@link JsonRpcParameters} instance from named parameters.
     */
    public static JsonRpcParameters of(Map<String, ?> namedParams) {
        requireNonNull(namedParams, "namedParams");
        return new JsonRpcParameters(copyParams(namedParams));
    }

    private static List<Object> copyParams(Iterable<?> params) {
        requireNonNull(params, "params");
        if (params instanceof ImmutableList) {
            //noinspection unchecked
            return (List<Object>) params;
        }

        // Note we do not use ImmutableList.copyOf() here,
        // because it does not allow a null element and we should allow a null argument.
        final List<Object> copy;
        if (params instanceof Collection) {
            copy = new ArrayList<>(((Collection<?>) params).size());
        } else {
            copy = new ArrayList<>(8);
        }

        for (Object p : params) {
            copy.add(p);
        }

        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> copyParams(Map<String, ?> params) {
        requireNonNull(params, "params");
        if (params.isEmpty()) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(new HashMap<>(params));
    }

    @Nullable
    private final List<Object> positional;

    @Nullable
    private final Map<String, Object> named;

    private JsonRpcParameters(List<Object> positional) {
        this.positional = positional;
        named = null;
    }

    private JsonRpcParameters(Map<String, Object> named) {
        positional = null;
        this.named = named;
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
     * Returns the parameters as an {@link Object}.
     * It returns either a {@link List} for positional parameters or a {@link Map} for named parameters.
     */
    public Object asObject() {
        if (positional != null) {
            return positional;
        } else {
            assert named != null;
            return named;
        }
    }

    /**
     * Returns {@code true} if there is no parameter.
     */
    public boolean isEmpty() {
        if (positional != null) {
            return positional.isEmpty();
        } else {
            assert named != null;
            return named.isEmpty();
        }
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
            assert named != null;
            return named;
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

        if (!(obj instanceof JsonRpcParameters)) {
            return false;
        }

        final JsonRpcParameters that = (JsonRpcParameters) obj;
        return Objects.equals(positional, that.positional) &&
               Objects.equals(named, that.named);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("positional", positional)
                          .add("named", named)
                          .toString();
    }
}
