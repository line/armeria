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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultDependencyInjector implements DependencyInjector {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDependencyInjector.class);

    private final Map<Class<?>, Object> singletons = new HashMap<>();
    private final List<AutoCloseable> closeablePrototypes = new ArrayList<>();
    private final Map<Class<?>, Supplier<?>> singletonSuppliers;
    private final Map<Class<?>, Supplier<?>> prototypes;

    DefaultDependencyInjector(Map<Class<?>, Supplier<?>> singletonSuppliers,
                              Map<Class<?>, Supplier<?>> prototypes) {
        this.singletonSuppliers = singletonSuppliers;
        this.prototypes = prototypes;
    }

    @Override
    public synchronized <T> T getInstance(Class<T> type) {
        final Object instance = singletons.get(type);
        if (instance != null) {
            //noinspection unchecked
            return (T) instance;
        }
        T supplied = getInstance(type, singletonSuppliers);
        if (supplied != null) {
            singletons.put(type, supplied);
            return supplied;
        }

        supplied = getInstance(type, prototypes);
        if (supplied instanceof AutoCloseable) {
            closeablePrototypes.add((AutoCloseable) supplied);
        }

        return supplied;
    }

    @Nullable
    private static <T> T getInstance(Class<T> type, Map<Class<?>, Supplier<?>> suppliers) {
        final Supplier<?> supplier = suppliers.get(type);
        if (supplier != null) {
            final Object supplied = supplier.get();
            requireNonNull(supplied, supplier + " returns null.");
            if (!type.isInstance(supplied)) {
                throw new IllegalArgumentException(supplied + " is not an instance of " + type.getName());
            }
            //noinspection unchecked
            return (T) supplied;
        }
        return null;
    }

    @Override
    public synchronized void close() {
        for (Object instance : singletons.values()) {
            if (instance instanceof AutoCloseable) {
                close((AutoCloseable) instance);
            }
        }
        singletons.clear();
        for (AutoCloseable closeable : closeablePrototypes) {
            close(closeable);
        }
        closeablePrototypes.clear();
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.warn("Unexpected exception while closing {}", closeable);
        }
    }
}
