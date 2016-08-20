/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

/**
 * A value mapped by {@link PathMappings}.
 *
 * @param <T> the type of the mapped value
 */
public final class PathMapped<T> {

    private static final PathMapped<Object> EMPTY = new PathMapped<>(null, null);

    /**
     * Returns a singleton instance of a {@link PathMapped} that represents a non-existent value.
     */
    @SuppressWarnings("unchecked")
    public static <T> PathMapped<T> empty() {
        return (PathMapped<T>) EMPTY;
    }

    /**
     * Creates a new {@link PathMapped} with the specified {@code mappedPath} and {@code value}.
     *
     * @param mappedPath the path translated by {@link PathMappings}
     * @param value      the value
     */
    public static <T> PathMapped<T> of(String mappedPath, T value) {
        return new PathMapped<>(requireNonNull(mappedPath, "mappedPath"),
                                requireNonNull(value, "value"));
    }

    @Nullable
    private final String mappedPath;
    @Nullable
    private final T value;

    private PathMapped(@Nullable String mappedPath, @Nullable T value) {
        assert mappedPath != null && value != null ||
               mappedPath == null && value == null;

        this.mappedPath = mappedPath;
        this.value = value;
    }

    /**
     * Returns {@code true} if and only if {@link PathMappings} found a matching value.
     */
    public boolean isPresent() {
        return mappedPath != null;
    }

    /**
     * Returns the path translated by {@link PathMappings}.
     *
     * @throws IllegalStateException if there's no match
     */
    public String mappedPath() {
        ensurePresence();
        return mappedPath;
    }

    /**
     * Returns the value.
     *
     * @throws IllegalStateException if there's no match
     */
    public T value() {
        ensurePresence();
        return value;
    }

    private void ensurePresence() {
        if (!isPresent()) {
            throw new IllegalStateException("mapping unavailable");
        }
    }

    @Override
    public String toString() {
        return isPresent() ? mappedPath() + " -> " + value() : "<empty>";
    }
}
