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
 * Maps a request path to a {@link MappedService}. Useful when building a service that delegates some or all
 * of its requests to other services. e.g. {@link SimpleCompositeService}.
 */
public class ServiceMapping implements Function<String, MappedService> {

    private final ThreadLocal<Map<String, MappedService>> threadLocalCache;
    private final List<Entry<PathMapping, Service>> patterns = new ArrayList<>();
    private boolean frozen;

    /**
     * Creates a new instance with the default thread-local cache size (1024).
     */
    public ServiceMapping() {
        this(1024);
    }

    /**
     * Creates a new instance with the specified thread-local LRU {@code cacheSize}.
     */
    public ServiceMapping(int cacheSize) {
        if (cacheSize < 0) {
            throw new IllegalArgumentException("cacheSize: " + cacheSize + " (expected: >= 0)");
        }

        if (cacheSize == 0) {
            threadLocalCache = null;
        } else {
            threadLocalCache = new ThreadLocal<Map<String, MappedService>>() {
                @Override
                protected Map<String, MappedService> initialValue() {
                    return new LruMap<>(cacheSize);
                }
            };
        }
    }

    /**
     * Adds the mapping from the specified {@link PathMapping} to the specified {@link Service}.
     *
     * @return {@code this}
     * @throws IllegalStateException if {@link #freeze()} or {@link #apply(String)} has been called already
     */
    public ServiceMapping add(PathMapping pathMapping, Service value) {
        if (frozen) {
            throw new IllegalStateException("can't add a new mapping once apply() was called");
        }

        patterns.add(new SimpleEntry<>(requireNonNull(pathMapping, "mapping"), requireNonNull(value, "value")));
        return this;
    }

    /**
     * Prevents adding a new mapping via {@link #add(PathMapping, Service)}.
     */
    public ServiceMapping freeze() {
        frozen = true;
        return this;
    }

    /**
     * Finds the {@link Service} whose {@link PathMapping} matches the specified {@code path}.
     *
     * @return a {@link MappedService} that wraps the matching {@link Service} if there's a match.
     *         {@link MappedService#empty()} if there's no match.
     */
    @Override
    public MappedService apply(String path) {
        freeze();

        // Look up the cache if the cache is available.
        final Map<String, MappedService> cache =
                threadLocalCache != null ? threadLocalCache.get() : null;

        if (cache != null) {
            final MappedService value = cache.get(path);
            if (value != null) {
                return value;
            }
        }

        // Cache miss or disabled cache
        MappedService result = MappedService.empty();
        final int size = patterns.size();
        for (int i = 0; i < size; i ++) {
            final Entry<PathMapping, Service> e = patterns.get(i);
            final String mappedPath = e.getKey().apply(path);
            if (mappedPath != null) {
                result = MappedService.of(mappedPath, e.getValue());
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
