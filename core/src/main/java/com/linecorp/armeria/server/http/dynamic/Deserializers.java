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

import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

/**
 * Provides deserializing functionality for reflection.
 *
 * @see Path
 * @see PathParam
 * @see DynamicHttpServiceBuilder
 */
final class Deserializers {

    private static final Map<Class<?>, Function<String, Object>> FUNCTIONS =
            ImmutableMap.<Class<?>, Function<String, Object>>builder()
                    .put(Byte.TYPE, Byte::parseByte)
                    .put(Short.TYPE, Short::parseShort)
                    .put(Integer.TYPE, Integer::parseInt)
                    .put(Long.TYPE, Long::parseLong)
                    .put(Float.TYPE, Float::parseFloat)
                    .put(Double.TYPE, Double::parseDouble)
                    .put(String.class, s -> s)
                    .build();

    /**
     * Deserialize given {@code str} to {@code T} type object. e.g., "42" -> 42.
     *
     * @throws IllegalArgumentException if {@code str} can't be deserialized to {@code T} type object.
     */
    public static <T> T deserialize(String str, Class<T> clazz) {
        if (!canDeserialize(clazz)) {
            throw new IllegalArgumentException("Class " + clazz.getSimpleName() + " can't be deserialized.");
        }
        Function<String, Object> function = FUNCTIONS.get(clazz);
        try {
            return (T) function.apply(str);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Can't deserialize " + str + " to type " + clazz.getSimpleName(), e);
        }
    }

    private static boolean canDeserialize(Class<?> clazz) {
        return FUNCTIONS.containsKey(clazz);
    }

    private Deserializers() {}
}
