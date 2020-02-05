/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

final class AnnotatedServiceTypeUtil {

    /**
     * Supported types and functions which convert a string to the desired type.
     */
    private static final Map<Class<?>, Function<String, ?>> supportedElementTypes =
            ImmutableMap.<Class<?>, Function<String, ?>>builder()
                    .put(Byte.TYPE, Byte::valueOf)
                    .put(Byte.class, Byte::valueOf)
                    .put(Short.TYPE, Short::valueOf)
                    .put(Short.class, Short::valueOf)
                    .put(Boolean.TYPE, Boolean::valueOf)
                    .put(Boolean.class, Boolean::valueOf)
                    .put(Integer.TYPE, Integer::valueOf)
                    .put(Integer.class, Integer::valueOf)
                    .put(Long.TYPE, Long::valueOf)
                    .put(Long.class, Long::valueOf)
                    .put(Float.TYPE, Float::valueOf)
                    .put(Float.class, Float::valueOf)
                    .put(Double.TYPE, Double::valueOf)
                    .put(Double.class, Double::valueOf)
                    .put(String.class, Function.identity())
                    .build();

    /**
     * Normalizes the specified container {@link Class}. Throws {@link IllegalArgumentException}
     * if it is not able to be normalized.
     */
    static Class<?> normalizeContainerType(Class<?> containerType) {
        if (containerType == Iterable.class ||
            containerType == List.class ||
            containerType == Collection.class) {
            return ArrayList.class;
        }
        if (containerType == Set.class) {
            return LinkedHashSet.class;
        }
        if (List.class.isAssignableFrom(containerType) ||
            Set.class.isAssignableFrom(containerType)) {
            try {
                // Only if there is a default constructor.
                containerType.getConstructor();
                return containerType;
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Unsupported container type: " + containerType.getName(),
                                                   cause);
            }
        }
        throw new IllegalArgumentException("Unsupported container type: " + containerType.getName());
    }

    /**
     * Validates whether the specified element {@link Class} is supported.
     * Throws {@link IllegalArgumentException} if it is not supported.
     */
    static Class<?> validateElementType(Class<?> clazz) {
        if (clazz.isEnum()) {
            return clazz;
        }
        if (supportedElementTypes.containsKey(clazz)) {
            return clazz;
        }
        throw new IllegalArgumentException("Parameter type '" + clazz.getName() + "' is not supported.");
    }

    /**
     * Converts the given {@code str} to {@code T} type object. e.g., "42" -> 42.
     *
     * @throws IllegalArgumentException if {@code str} can't be deserialized to {@code T} type object.
     */
    @SuppressWarnings("unchecked")
    static <T> T stringToType(String str, Class<T> clazz) {
        try {
            final Function<String, ?> func = supportedElementTypes.get(clazz);
            if (func != null) {
                return (T) func.apply(str);
            }
        } catch (NumberFormatException e) {
            throw e;
        } catch (Throwable cause) {
            throw new IllegalArgumentException(
                    "Can't convert '" + str + "' to type '" + clazz.getSimpleName() + "'.", cause);
        }

        throw new IllegalArgumentException(
                "Can't convert '" + str + "' to type '" + clazz.getSimpleName() + "'.");
    }

    private AnnotatedServiceTypeUtil() {}
}
