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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

final class DefaultDependencyInjector implements DependencyInjector {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDependencyInjector.class);

    private final Map<Class<?>, Object> singletons = new HashMap<>();

    private final Lock lock = new ReentrantShortLock();
    private boolean isShutdown;

    DefaultDependencyInjector(Iterable<Object> singletons) {
        requireNonNull(singletons, "singletons");
        for (Object singleton : singletons) {
            requireNonNull(singleton, "singleton");
            this.singletons.put(singleton.getClass(), singleton);
        }
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        lock.lock();
        try {
            if (isShutdown) {
                throw new IllegalStateException("Already shut down");
            }
            final Object instance = singletons.get(type);
            if (instance != null) {
                //noinspection unchecked
                return (T) instance;
            }
            return null;
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
            for (Object instance : singletons.values()) {
                if (instance instanceof AutoCloseable) {
                    close((AutoCloseable) instance);
                }
            }
            singletons.clear();
        } finally {
            lock.unlock();
        }
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.warn("Unexpected exception while closing {}", closeable, e);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("singletons", singletons)
                          .toString();
    }
}
