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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.util.Exceptions;

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
                    .put(Duration.class, Duration::parse)
                    .put(Instant.class, Instant::parse)
                    .put(LocalDate.class, LocalDate::parse)
                    .put(LocalDateTime.class, LocalDateTime::parse)
                    .put(LocalTime.class, LocalTime::parse)
                    .put(OffsetDateTime.class, OffsetDateTime::parse)
                    .put(OffsetTime.class, OffsetTime::parse)
                    .put(Period.class, Period::parse)
                    .put(ZonedDateTime.class, ZonedDateTime::parse)
                    .put(ZoneId.class, ZoneId::of)
                    .put(ZoneOffset.class, ZoneOffset::of)
                    .put(AsciiString.class, AsciiString::new)
                    .put(String.class, Function.identity())
                    .put(CharSequence.class, Function.identity())
                    .put(Object.class, Function.identity())
                    .build();

    /**
     * "Cache" of converters from string to the Class in the key.
     */
    private static final Map<Class<?>, Function<String, ?>> convertExternalTypes =
            new MapMaker().weakKeys().makeMap();

    private static final Map<String, Boolean> stringToBooleanMap =
            ImmutableMap.<String, Boolean>builder()
                    .put("true", true)
                    .put("1", true)
                    .put("false", false)
                    .put("0", false)
                    .build();

    /**
     * Try to get a public static method {@link MethodHandle} with a single {@link String} argument
     * in {@code clazz}.
     * @param <T> the expected result
     * @param clazz the class being introspected
     * @param methodName the method expected
     * @return a function that takes a {@link String} and produces a {@link T}
     */
    @Nullable
    private static <T> MethodHandle getPublicStaticMethodHandle(final Class<T> clazz,
                                                                final String methodName) {
        try {
            final MethodType methodType = MethodType.methodType(clazz, String.class);
            return MethodHandles.publicLookup().findStatic(clazz, methodName, methodType);
        } catch (Throwable t) {
            // No valid public static method found
            return null;
        }
    }

    /**
     * Try to get a constructor {@link MethodHandle} with a single {@link String} argument in {@code clazz}.
     * @param <T> the expected result
     * @param clazz the class being introspected
     * @return a function that takes a {@link String} and produces a {@link T}
     */
    @Nullable
    private static <T> MethodHandle getStringConstructorMethodHandle(final Class<T> clazz) {
        try {
            final MethodType methodType = MethodType.methodType(void.class, String.class);
            return MethodHandles.publicLookup().findConstructor(clazz, methodType);
        } catch (Throwable t) {
            // No valid constructor found
            return null;
        }
    }

    /**
     * Try to get a function that can create an instance of type {@code clazz} with a single
     * {@link String} argument.
     * @param <T> the expected resulting type
     * @param clazz the class being introspected
     * @return a function that takes a {@link String} and produces a {@code T}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    static <T> Function<String, T> getCreatorMethod(Class<T> clazz) {
        final MethodHandle methodHandle = Stream.of("of", "valueOf", "fromString")
                     .map((methodName) -> getPublicStaticMethodHandle(clazz, methodName))
                     .filter(Objects::nonNull)
                     .findFirst()
                     .orElseGet(() -> getStringConstructorMethodHandle(clazz));
        if (methodHandle == null) {
            return null;
        }
        return (str) -> {
            try {
                return (T) methodHandle.invokeWithArguments(str);
            } catch (InvocationTargetException e) {
                return Exceptions.throwUnsafely(e.getCause());
            } catch (Throwable t) {
                return Exceptions.throwUnsafely(t);
            }
        };
    }

    /**
     * Converts the given {@code str} to {@code T} type object. e.g., "42" -> 42.
     *
     * @throws IllegalArgumentException if {@code str} can't be deserialized to {@code T} type object.
     */
    @SuppressWarnings("unchecked")
    static <T> T stringToType(String str, Class<T> clazz) {
        try {
            Function<String, ?> func = supportedElementTypes.get(clazz);
            if (func == null) {
                func = convertExternalTypes.computeIfAbsent(clazz, AnnotatedServiceTypeUtil::getCreatorMethod);
            }
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
