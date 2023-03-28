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
package com.linecorp.armeria.internal.common;

import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.annotation.Nullable;

public final class ReflectiveDependencyInjector implements DependencyInjector {

    private static final Logger logger = LoggerFactory.getLogger(ReflectiveDependencyInjector.class);

    @Nullable
    public static <T> T create(Class<? extends T> type, @Nullable Map<Class<?>, Object> instanceStorage) {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(type, withParametersCount(0)), null);
        if (constructor == null) {
            return null;
        }
        constructor.setAccessible(true);
        final T instance;
        try {
            instance = constructor.newInstance();
            if (instanceStorage != null) {
                instanceStorage.put(type, instance);
            }
        } catch (Throwable t) {
            throw new IllegalArgumentException("cannot create an instance of " + type.getName(), t);
        }
        return instance;
    }

    private final ReentrantLock lock = new ReentrantLock();

    private final Map<Class<?>, Object> instances = new HashMap<>();

    private boolean isShutdown;

    @Override
    public <T> T getInstance(Class<T> type) {
        lock.lock();
        try {
            if (isShutdown) {
                throw new IllegalStateException("Already shut down");
            }
            final Object instance = instances.get(type);
            if (instance != null) {
                //noinspection unchecked
                return (T) instance;
            }
            return create(type, instances);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (isShutdown) {
                return;
            }
            isShutdown = true;
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
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("instances", instances)
                          .toString();
    }
}
