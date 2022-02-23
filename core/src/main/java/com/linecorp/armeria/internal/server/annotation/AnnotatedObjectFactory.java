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

package com.linecorp.armeria.internal.server.annotation;

import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.google.common.collect.Iterables;

/**
 * A utility class for getting cached annotated objects.
 */
public final class AnnotatedObjectFactory {

    /**
     * An instance map for reusing from {@link AnnotatedServiceFactory} and {@link DecoratorUtil}.
     */
    private static final ClassValue<Object> instanceCache = new ClassValue<Object>() {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                return getInstance0(type);
            } catch (Exception e) {
                throw new IllegalStateException("A class must have an accessible default constructor: " +
                                                type.getName(), e);
            }
        }
    };

    /**
     * Returns a cached instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     */
    static <T> T getInstance(Annotation annotation, Class<T> expectedType) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> clazz = (Class<? extends T>) invokeValueMethod(annotation);
            return expectedType.cast(instanceCache.get(clazz));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "A class specified in @" + annotation.annotationType().getSimpleName() +
                    " annotation cannot be cast to " + expectedType, e);
        }
    }

    /**
     * Returns a cached instance of the specified {@link Class}.
     */
    static <T> T getInstance(Class<T> clazz) {
        @SuppressWarnings("unchecked")
        final T casted = (T) instanceCache.get(clazz);
        return casted;
    }

    /**
     * Returns an object which is returned by {@code value()} method of the specified annotation {@code a}.
     */
    static Object invokeValueMethod(Annotation a) {
        try {
            final Method method = Iterables.getFirst(getMethods(a.annotationType(), withName("value")), null);
            assert method != null : "No 'value' method is found from " + a;
            return method.invoke(a);
        } catch (Exception e) {
            throw new IllegalStateException("An annotation @" + a.annotationType().getSimpleName() +
                                            " must have a 'value' method", e);
        }
    }

    private static <T> T getInstance0(Class<? extends T> clazz) throws Exception {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
        assert constructor != null : "No default constructor is found from " + clazz.getName();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private AnnotatedObjectFactory() {}
}
