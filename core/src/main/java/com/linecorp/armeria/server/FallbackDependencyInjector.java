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

import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

enum FallbackDependencyInjector implements DependencyInjector {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(FallbackDependencyInjector.class);

    private static final Map<Class<?>, Object> instances = new HashMap<>();

    @Override
    public synchronized <T> T getInstance(Class<T> type) {
        final Object instance = instances.get(type);
        if (instance != null) {
            //noinspection unchecked
            return (T) instance;
        }
        return create(type);
    }

    private static <T> T create(Class<? extends T> type) {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(type, withParametersCount(0)), null);
        if (constructor == null) {
            throw new IllegalArgumentException("cannot inject dependency for " + type.getName() +
                                               ". Use " + DependencyInjector.class.getName() +
                                               " or add default constructor to create the instance.");
        }
        constructor.setAccessible(true);
        final T instance;
        try {
            instance = constructor.newInstance();
            instances.put(type, instance);
        } catch (Throwable t) {
            throw new IllegalArgumentException("cannot create an instance of " + type.getName(), t);
        }
        return instance;
    }

    @Override
    public synchronized void close() {
        for (Object instance : instances.values()) {
            if (instance instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) instance).close();
                } catch (Exception e) {
                    logger.warn("Unexpected exception while closing {}", instance);
                }
            }
        }
        instances.clear();
    }
}
