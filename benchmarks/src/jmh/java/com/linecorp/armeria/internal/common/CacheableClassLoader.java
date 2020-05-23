/*
 * Copyright 2020 LINE Corporation
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.MapMaker;

final class CacheableClassLoader {

    private static final Map<String, Class<?>> cache = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> weakValueCache = new MapMaker().weakValues().makeMap();

    static Class<?> loadWithConcurrentHashMap(String fqcn) throws ClassNotFoundException {
        return loadFromCache(fqcn, cache);
    }

    static Class<?> loadWithConcurrentHashMap(String fqcn, boolean initialize) throws ClassNotFoundException {
        final Class<?> clazz = cache.get(fqcn);
        if (clazz == null) {
            return cache.computeIfAbsent(fqcn, key -> {
                try {
                    return Class.forName(key, initialize, CacheableClassLoader.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    return CacheableClassLoader.class;
                }
            });
        }
        return clazz;
    }

    static Class<?> loadWithGuavaHashMap(String fqcn) throws ClassNotFoundException {
        return loadFromCache(fqcn, weakValueCache);
    }

    private static Class<?> loadFromCache(String fqcn, Map<String, Class<?>> cache) {
        final Class<?> clazz = cache.get(fqcn);
        if (clazz == null) {
            return cache.computeIfAbsent(fqcn, key -> {
                try {
                    return Class.forName(key);
                } catch (ClassNotFoundException e) {
                    return CacheableClassLoader.class;
                }
            });
        }
        return clazz;
    }

    private CacheableClassLoader() {}
}
