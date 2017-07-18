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
public class PathMappings<T> implements Function<PathMappingContext, PathMapped<T>> {

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
            threadLocalCache = ThreadLocal.withInitial(() -> new LruMap<>(cacheSize));
        }
    }

    /**
     * Adds the mapping from the specified {@link PathMapping} to the specified {@code value}.
     *
     * @return {@code this}
     * @throws IllegalStateException if {@link #freeze()} or {@link #apply(PathMappingContext)} has been called
     *                               already
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
     * Finds the {@link Service} whose {@link PathMapping} matches the specified {@link PathMappingContext}.
     *
     * @return a {@link PathMapped} that wraps the matching value if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    @Override
    public PathMapped<T> apply(PathMappingContext mappingCtx) {
        freeze();

        // Look up the cache if the cache is available and query string does not exist.
        final Map<String, PathMapped<T>> cache =
                mappingCtx.query() == null && threadLocalCache != null ? threadLocalCache.get() : null;

        if (cache != null) {
            final PathMapped<T> value = cache.get(mappingCtx.summary());
            if (value != null) {
                return value;
            }
        }

        // Cache miss or disabled cache
        PathMapped<T> result = PathMapped.empty();
        final int size = patterns.size();
        for (int i = 0; i < size; i++) {
            final Entry<PathMapping, T> e = patterns.get(i);
            final PathMapping mapping = e.getKey();
            final PathMappingResult mappingResult = mapping.apply(mappingCtx);
            if (mappingResult.isPresent()) {
                //
                // The services are sorted as follows:
                //
                // 1) annotated service with method and media type negotiation (consumable and producible)
                // 2) annotated service with method and producible media type negotiation
                // 3) annotated service with method and consumable media type negotiation
                // 4) annotated service with method negotiation
                // 5) the other services (in a registered order)
                //
                // 1) and 2) may produce a score between the lowest and the highest because they should
                // negotiate the produce type with the value of 'Accept' header.
                // 3), 4) and 5) always produces the lowest score.
                //

                // Found the best matching.
                if (mappingResult.isHighestScore()) {
                    result = PathMapped.of(mapping, mappingResult, e.getValue());
                    break;
                }

                // This means that the 'mappingResult' is produced by one of 3), 4) and 5). So we have no more
                // chance to find a better matching from now.
                if (mappingResult.isLowestScore()) {
                    if (!result.isPresent()) {
                        result = PathMapped.of(mapping, mappingResult, e.getValue());
                    }
                    break;
                }

                // We have still a chance to find a better matching.
                if (result.isPresent()) {
                    if (mappingResult.score() > result.mappingResult().score()) {
                        // Replace the candidate with the new one only if the score is better.
                        // If the score is same, we respect the order of service registration.
                        result = PathMapped.of(mapping, mappingResult, e.getValue());
                    }
                } else {
                    // Keep the result as a candidate.
                    result = PathMapped.of(mapping, mappingResult, e.getValue());
                }
            }
        }

        // Cache the result.
        if (cache != null) {
            cache.put(mappingCtx.summary(), result);
        }

        return result;
    }

    @Override
    public String toString() {
        return patterns.toString();
    }
}
