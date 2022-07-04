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

import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withName;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.DependencyInjector;

/**
 * A utility class for getting cached annotated objects.
 */
final class AnnotatedObjectFactory {

    /**
     * Returns an instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     */
    static <T> T getInstance(Annotation annotation, Class<T> expectedType,
                             DependencyInjector dependencyInjector) {
        @SuppressWarnings("unchecked")
        final Class<? extends T> type = (Class<? extends T>) invokeValueMethod(annotation);
        final T instance = dependencyInjector.getInstance(type);
        if (instance != null) {
            if (!expectedType.isInstance(instance)) {
                throw new IllegalArgumentException(
                        "A class specified in @" + annotation.annotationType().getSimpleName() +
                        " annotation cannot be cast to " + expectedType);
            }
            return instance;
        }

        throw new IllegalArgumentException("cannot inject the dependency for " + type.getName() +
                                           ". Use " + DependencyInjector.class.getName() +
                                           " or add a default constructor to create the instance.");
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

    private AnnotatedObjectFactory() {}
}
