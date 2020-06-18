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

import java.time.Period;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;

import io.netty.util.AsciiString;

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
                    .put(Boolean.TYPE, AnnotatedServiceTypeUtil::parseBoolean)
                    .put(Boolean.class, AnnotatedServiceTypeUtil::parseBoolean)
                    .put(Integer.TYPE, Integer::valueOf)
                    .put(Integer.class, Integer::valueOf)
                    .put(Long.TYPE, Long::valueOf)
                    .put(Long.class, Long::valueOf)
                    .put(Float.TYPE, Float::valueOf)
                    .put(Float.class, Float::valueOf)
                    .put(Double.TYPE, Double::valueOf)
                    .put(Double.class, Double::valueOf)
                    .put(UUID.class, UUID::fromString)
                    .put(Period.class, Period::parse)
                    .put(AsciiString.class, AsciiString::new)
                    .put(String.class, Function.identity())
                    .put(CharSequence.class, Function.identity())
                    .put(Object.class, Function.identity())
                    .build();

    private static final Map<String, Boolean> stringToBooleanMap =
            ImmutableMap.<String, Boolean>builder()
                    .put("true", true)
                    .put("1", true)
                    .put("false", false)
                    .put("0", false)
                    .build();

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

    private static Boolean parseBoolean(String s) {
        final Boolean result = stringToBooleanMap.get(Ascii.toLowerCase(s));
        if (result == null) {
            throw new IllegalArgumentException("must be one of " + stringToBooleanMap.keySet() + ": " + s);
        }
        return result;
    }

    private AnnotatedServiceTypeUtil() {}
}
