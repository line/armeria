/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * A value mapped by {@link Router}.
 *
 * @param <T> the type of the mapped value
 */
public final class PathMapped<T> {

    private static final PathMapped<Object> EMPTY = new PathMapped<>(null, PathMappingResult.empty(), null);

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
     * @param mappingResult the result of {@link PathMapping#apply(PathMappingContext)}
     * @param value  the value
     */
    static <T> PathMapped<T> of(PathMapping mapping, PathMappingResult mappingResult, T value) {
        requireNonNull(mapping, "mapping");
        requireNonNull(mappingResult, "mappingResult");
        requireNonNull(value, "value");
        if (!mappingResult.isPresent()) {
            throw new IllegalArgumentException("mappingResult: " + mappingResult + " (must be present)");
        }

        return new PathMapped<>(mapping, mappingResult, value);
    }

    @Nullable
    private final PathMapping mapping;
    private final PathMappingResult mappingResult;
    @Nullable
    private final T value;

    private PathMapped(@Nullable PathMapping mapping, PathMappingResult mappingResult, @Nullable T value) {
        assert mapping != null && value != null ||
               mapping == null && value == null;

        this.mapping = mapping;
        this.mappingResult = mappingResult;
        this.value = value;
    }

    /**
     * Returns {@code true} if and only if {@link Router} found a matching value.
     */
    public boolean isPresent() {
        return mapping != null;
    }

    /**
     * Returns the {@link PathMapping} which matched the path.
     *
     * @throws IllegalStateException if there's no match
     */
    public PathMapping mapping() {
        ensurePresence();
        return mapping;
    }

    /**
     * Returns the {@link PathMappingResult}.
     *
     * @throws IllegalStateException if there's no match
     */
    public PathMappingResult mappingResult() {
        ensurePresence();
        return mappingResult;
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
        if (isPresent()) {
            return MoreObjects.toStringHelper(this)
                                  .add("mapping", mapping)
                                  .add("mappingResult", mappingResult)
                                  .add("value", value)
                                  .toString();
        } else {
            return getClass().getSimpleName() + "{<empty>}";
        }
    }
}
