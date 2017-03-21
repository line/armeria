/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.Sets;

import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.PathParamExtractor;

/**
 * Builds a new {@link DynamicHttpService}.
 */
public final class DynamicHttpServiceBuilder {

    private final Map<Class<?>, ResponseConverter> converters = new HashMap<>();
    private final List<DynamicHttpFunctionEntry> entries = new ArrayList<>();

    /**
     * Adds a mapping from {@link Class} to {@link ResponseConverter}.
     */
    public DynamicHttpServiceBuilder addConverter(Class<?> type, ResponseConverter converter) {
        this.converters.put(type, converter);
        return this;
    }

    /**
     * Adds a new dynamic-mapped method, invoked with given {@link HttpMethod} and (dynamically-bound) path,
     * runs synchronously.
     */
    public DynamicHttpServiceBuilder addMapping(HttpMethod method, String path, DynamicHttpFunction function) {
        return addMapping(EnumSet.of(method), path, function);
    }

    /**
     * Adds a new dynamic-mapped method, invoked with given {@link HttpMethod} and (dynamically-bound) path,
     * runs synchronously.
     */
    public DynamicHttpServiceBuilder addMapping(HttpMethod method, String path, DynamicHttpFunction function,
                                                ResponseConverter converter) {
        return addMapping(EnumSet.of(method), path, function, converter);
    }

    /**
     * Adds a new dynamic-mapped method, invoked with given {@link HttpMethod}s and (dynamically-bound) path,
     * runs synchronously.
     */
    public DynamicHttpServiceBuilder addMapping(Iterable<HttpMethod> methods, String path,
                                                DynamicHttpFunction function) {
        DynamicHttpFunction f = DynamicHttpFunctions.of(function, converters);
        DynamicHttpFunctionEntry entry = new DynamicHttpFunctionEntry(
                Sets.immutableEnumSet(methods), new PathParamExtractor(path), f);
        validate(entry);
        entries.add(entry);
        return this;
    }

    /**
     * Adds a new dynamic-mapped method, invoked with given {@link HttpMethod}s and (dynamically-bound) path,
     * runs synchronously.
     */
    public DynamicHttpServiceBuilder addMapping(Iterable<HttpMethod> methods, String path,
                                                DynamicHttpFunction function, ResponseConverter converter) {
        DynamicHttpFunction composed = DynamicHttpFunctions.of(function, converter);
        return addMapping(methods, path, composed);
    }

    /**
     * Add the {@link Path} annotated methods in given object.
     *
     * @see Path
     * @see PathParam
     */
    public DynamicHttpServiceBuilder addMappings(Object strategy) {
        for (DynamicHttpFunctionEntry entry : Methods.entries(strategy, converters)) {
            validate(entry);
            entries.add(entry);
        }
        return this;
    }

    /**
     * Check whether the mapping pattern for {@code entry} already exists.
     *
     * @throws IllegalArgumentException if there is already a {@link DynamicHttpFunctionEntry} whose mapping
     *     pattern overlaps with {@code entry}.
     */
    private void validate(DynamicHttpFunctionEntry entry) {
        requireNonNull(entry, "entry");
        Optional<DynamicHttpFunctionEntry> overlapped = entries.stream()
                                                               .filter(e -> e.overlaps(entry))
                                                               .findFirst();
        if (overlapped.isPresent()) {
            throw new IllegalArgumentException(
                    "Mapping conflicts: " + entry.toString() + ", " + overlapped.get().toString());
        }
    }

    /**
     * Creates a new {@link DynamicHttpService} with the configuration properties set so far.
     */
    public DynamicHttpService build() {
        return new DynamicHttpService(entries);
    }
}
