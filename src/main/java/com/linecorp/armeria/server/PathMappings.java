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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import com.linecorp.armeria.common.util.LruMap;
import com.linecorp.armeria.server.composition.SimpleCompositeService;

/**
 * Maps a request path to a value associated with a matching {@link PathMapping}. Useful when building a
 * service that delegates some or all of its requests to other services. e.g. {@link SimpleCompositeService}.
 *
 * @param <T> the type of the mapped value
 */
public class PathMappings<T> implements Function<String, PathMapped<T>> {

    private final ThreadLocal<Map<String, PathMapped<T>>> threadLocalCache;
    private final List<Entry<PathMapping, T>> patterns = new ArrayList<>();
    private boolean frozen;

    /**
     * Creates a new instance with the default thread-local cache size (1024).
     */
    public PathMappings() {
        this(1024);
    }

    /**
     * Creates a new instance with the specified thread-local LRU {@code cacheSize}.
     */
    public PathMappings(int cacheSize) {
        if (cacheSize < 0) {
            throw new IllegalArgumentException("cacheSize: " + cacheSize + " (expected: >= 0)");
        }

        if (cacheSize == 0) {
            threadLocalCache = null;
        } else {
            threadLocalCache = new ThreadLocal<Map<String, PathMapped<T>>>() {
                @Override
                protected Map<String, PathMapped<T>> initialValue() {
                    return new LruMap<>(cacheSize);
                }
            };
        }
    }

    /**
     * Adds the mapping from the specified {@link PathMapping} to the specified {@code value}.
     *
     * @return {@code this}
     * @throws IllegalStateException if {@link #freeze()} or {@link #apply(String)} has been called already
     */
    public PathMappings<T> add(PathMapping pathMapping, T value) {
        if (frozen) {
            throw new IllegalStateException("can't add a new mapping once apply() was called");
        }

        requireNonNull(pathMapping, "mapping");
        requireNonNull(value, "value");

        patterns.add(new SimpleEntry<>(pathMapping, value));
        return this;
    }

    /**
     * Prevents adding a new mapping via {@link #add(PathMapping, Object)}.
     */
    public PathMappings<T> freeze() {
        frozen = true;
        return this;
    }

    /**
     * Finds the {@link Service} whose {@link PathMapping} matches the specified {@code path}.
     *
     * @return a {@link PathMapped} that wraps the matching value if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    @Override
    public PathMapped<T> apply(String path) {
        freeze();

        // Look up the cache if the cache is available.
        final Map<String, PathMapped<T>> cache =
                threadLocalCache != null ? threadLocalCache.get() : null;

        if (cache != null) {
            final PathMapped<T> value = cache.get(path);
            if (value != null) {
                return value;
            }
        }

        // Cache miss or disabled cache
        PathMapped<T> result = PathMapped.empty();
        final int size = patterns.size();
        for (int i = 0; i < size; i ++) {
            final Entry<PathMapping, T> e = patterns.get(i);
            final String mappedPath = e.getKey().apply(path);
            if (mappedPath != null) {
                result = PathMapped.of(mappedPath, e.getValue());
                break;
            }
        }

        // Cache the result.
        if (cache != null) {
            cache.put(path, result);
        }

        return result;
    }

    @Override
    public String toString() {
        return patterns.toString();
    }


}
