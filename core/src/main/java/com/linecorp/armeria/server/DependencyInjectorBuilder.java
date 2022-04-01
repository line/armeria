/*
 * Copyright 2022 LINE Corporation
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

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableMap;

/**
 * Builds a {@link DependencyInjector}.
 */
public final class DependencyInjectorBuilder {

    private final Map<Class<?>, Supplier<?>> singletons = new HashMap<>();
    private final Map<Class<?>, Supplier<?>> prototypes = new HashMap<>();

    DependencyInjectorBuilder() {}

    /**
     * Sets the {@link Supplier} to inject the singleton instance of {@link Class}. {@link Supplier#get()} is
     * called only once and the supplied instance is reused. The instance is
     * {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@linkplain Server#stop() server is stopped}.
     */
    public <T> DependencyInjectorBuilder singleton(Class<T> type, Supplier<T> supplier) {
        requireNonNull(type, "type");
        requireNonNull(supplier, "supplier");
        checkDuplicateType(prototypes, "prototype", type);
        singletons.put(type, supplier);
        return this;
    }

    /**
     * Sets the {@link Supplier}s to inject the singleton instance of the corresponding {@link Class}.
     * {@link Supplier#get()} is called only once for a {@link Class} and the supplied instance is reused.
     * The instance is {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@linkplain Server#stop() server is stopped}.
     */
    public DependencyInjectorBuilder singletons(Map<Class<?>, Supplier<?>> singletons) {
        requireNonNull(singletons, "singletons");
        for (Entry<Class<?>, Supplier<?>> entry : singletons.entrySet()) {
            requireNonNull(entry.getValue(), "singletons contains null.");
        }
        for (Class<?> type : singletons.keySet()) {
            checkDuplicateType(prototypes, "prototype", type);
        }
        this.singletons.putAll(singletons);
        return this;
    }

    /**
     * Sets the {@link Supplier} to inject the prototype instance of {@link Class}.
     * Unlike {@link #singleton(Class, Supplier)}, {@link Supplier#get()} is called every time when an
     * instance of {@link Class} is needed.
     * The {@linkplain Supplier#get() supplied instance} is {@linkplain AutoCloseable#close() closed}
     * if it implements {@link AutoCloseable} when the {@linkplain Server#stop() server is stopped}.
     */
    public <T> DependencyInjectorBuilder prototype(Class<T> type, Supplier<T> supplier) {
        requireNonNull(type, "type");
        requireNonNull(supplier, "supplier");
        checkDuplicateType(singletons, "singleton", type);
        prototypes.put(type, supplier);
        return this;
    }

    /**
     * Sets the {@link Supplier}s to inject the prototype instance of the corresponding {@link Class}.
     * Unlike {@link #singletons(Map)}, {@link Supplier#get()} is called every time when an
     * instance of the corresponding {@link Class} is needed.
     * The {@linkplain Supplier#get() supplied instance} is {@linkplain AutoCloseable#close() closed}
     * if it implements {@link AutoCloseable} when the {@linkplain Server#stop() server is stopped}.
     */
    public DependencyInjectorBuilder prototypes(Map<Class<?>, Supplier<?>> prototypes) {
        requireNonNull(prototypes, "prototypes");
        for (Entry<Class<?>, Supplier<?>> entry : prototypes.entrySet()) {
            requireNonNull(entry.getValue(), "prototypes contains null.");
        }
        for (Class<?> type : prototypes.keySet()) {
            checkDuplicateType(singletons, "singleton", type);
        }
        this.prototypes.putAll(prototypes);
        return this;
    }

    private static void checkDuplicateType(Map<Class<?>, Supplier<?>> map, String methodName, Class<?> type) {
        if (map.containsKey(type)) {
            throw new IllegalArgumentException(type.getName() + " is already set via " + methodName + "().");
        }
    }

    /**
     * Returns a newly-created {@link DependencyInjector} based on the properties set so far.
     */
    public DependencyInjector build() {
        return new DefaultDependencyInjector(ImmutableMap.copyOf(singletons),
                                             ImmutableMap.copyOf(prototypes));
    }
}
