/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.metric;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import com.google.common.collect.MapMaker;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.util.MeterId;

/**
 * A utility that prevents double instantiation of an object for a certain {@link MeterId}. This can be useful
 * when you need to make sure you do not register the same {@link Meter} more than once.
 *
 * @see RequestMetricSupport
 * @see CaffeineMetricSupport
 */
public final class MicrometerUtil {

    private static final ConcurrentMap<MeterRegistry, ConcurrentMap<MeterId, Object>> map =
            new MapMaker().weakKeys().makeMap();

    /**
     * Associates a newly-created object with the specified {@link MeterId} or returns an existing one if
     * exists already.
     *
     * @param id the {@link MeterId} of the object
     * @param type the type of the object created by {@code factory}
     * @param factory a factory that creates an object of {@code type}, to be associated with {@code id}
     *
     * @return the object of {@code type}, which may or may not be created by {@code factory}
     * @throws IllegalStateException if there is already an object of different class associated
     *                               for the same {@link MeterId}
     */
    public static <T> T register(MeterRegistry registry, MeterId id, Class<T> type,
                                 BiFunction<MeterRegistry, MeterId, T> factory) {
        requireNonNull(registry, "registry");
        requireNonNull(id, "id");
        requireNonNull(type, "type");
        requireNonNull(factory, "factory");

        final ConcurrentMap<MeterId, Object> objects =
                map.computeIfAbsent(registry, unused -> new ConcurrentHashMap<>());
        final Object object = objects.computeIfAbsent(id, i -> factory.apply(registry, i));

        if (!type.isInstance(object)) {
            throw new IllegalStateException(
                    "An object of different type has been registered already for id: " + id +
                    " (expected: " + type.getName() +
                    ", actual: " + object.getClass().getName() + ')');
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) object;
        return cast;
    }

    private MicrometerUtil() {}
}
