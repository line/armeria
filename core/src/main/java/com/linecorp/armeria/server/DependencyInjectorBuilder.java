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
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a {@link DependencyInjector}.
 */
@UnstableApi
public final class DependencyInjectorBuilder {

    private final Map<Class<?>, Object> singletons = new HashMap<>();
    private final Map<Class<?>, Supplier<?>> singletonSuppliers = new HashMap<>();
    private final Map<Class<?>, Supplier<?>> prototypes = new HashMap<>();

    DependencyInjectorBuilder() {}

    /**
     * Sets the {@link Supplier} to inject the singleton instance of {@link Class}. {@link Supplier#get()} is
     * called only once and the supplied instance is reused. The instance is
     * {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@link Server} stops.
     */
    public <T> DependencyInjectorBuilder singleton(Class<T> type, Supplier<T> supplier) {
        requireNonNull(type, "type");
        requireNonNull(supplier, "supplier");
        checkDuplicateType(singletons, "singletons", type);
        checkDuplicateType(prototypes, "prototype", type);
        singletonSuppliers.put(type, supplier);
        return this;
    }

    /**
     * Sets the singleton instances to inject.
     * The instances are {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@link Server} stops.
     */
    public DependencyInjectorBuilder singletons(Object... singletons) {
        return singletons(ImmutableList.copyOf(requireNonNull(singletons, "singletons")));
    }

    /**
     * Sets the singleton instances to inject.
     * The instances are {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@link Server} stops.
     */
    public DependencyInjectorBuilder singletons(Iterable<Object> singletons) {
        requireNonNull(singletons, "singletons");
        for (Object singleton : singletons) {
            requireNonNull(singleton, "singleton");
            final Class<?> type = singleton.getClass();
            checkDuplicateType(singletonSuppliers, "singleton", type);
            checkDuplicateType(prototypes, "prototype", type);
        }
        for (Object singleton : singletons) {
            this.singletons.put(singleton.getClass(), singleton);
        }
        return this;
    }

    /**
     * Sets the {@link Supplier} to inject the prototype instance of {@link Class}.
     * Unlike {@link #singleton(Class, Supplier)}, {@link Supplier#get()} is called every time when an
     * instance of {@link Class} is needed.
     * The {@linkplain Supplier#get() supplied instance} is {@linkplain AutoCloseable#close() closed}
     * if it implements {@link AutoCloseable} when the {@link Server} stops.
     */
    public <T> DependencyInjectorBuilder prototype(Class<T> type, Supplier<T> supplier) {
        requireNonNull(type, "type");
        requireNonNull(supplier, "supplier");
        checkDuplicateType(singletons, "singletons", type);
        checkDuplicateType(singletonSuppliers, "singleton", type);
        prototypes.put(type, supplier);
        return this;
    }

    private static void checkDuplicateType(Map<Class<?>, ?> map, String methodName, Class<?> type) {
        if (map.containsKey(type)) {
            throw new IllegalArgumentException(type.getName() + " is already set via " + methodName + "().");
        }
    }

    /**
     * Returns a newly-created {@link DependencyInjector} based on the properties set so far.
     */
    public DependencyInjector build() {
        return new DefaultDependencyInjector(ImmutableMap.copyOf(singletons),
                                             ImmutableMap.copyOf(singletonSuppliers),
                                             ImmutableMap.copyOf(prototypes));
    }
}
