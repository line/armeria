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

import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.invokeMethod;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.invokeValueMethod;
import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.server.annotation.CreationMode;

/**
 * A utility class for getting cached annotated objects.
 */
final class AnnotatedObjectFactory {

    /**
     * An instance map for reused converters, exception handlers and decorators.
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
     * Returns an instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     */
    @SuppressWarnings("unchecked")
    static <T> T getInstance(Annotation annotation, Class<T> expectedType,
                             DependencyInjector dependencyInjector) {
        final Class<? extends T> type = (Class<? extends T>) invokeValueMethod(annotation);
        final CreationMode mode = (CreationMode) invokeMethod(annotation, "mode");

        final T instance;

        if (mode == CreationMode.INJECTION) {
            instance = dependencyInjector.getInstance(type);
        } else {
            assert mode == CreationMode.REFLECTION;
            instance = (T) instanceCache.get(type);
        }

        if (instance != null) {
            if (!expectedType.isInstance(instance)) {
                throw new IllegalArgumentException(
                        "A class specified in @" + annotation.annotationType().getSimpleName() +
                        " annotation cannot be cast to " + expectedType);
            }
            return instance;
        }

        throw new IllegalArgumentException("Cannot instantiate the dependency for " + type.getName() +
                                           ". Use " + DependencyInjector.class.getName() +
                                           " or add a default constructor to create the instance.");
    }

    private static <T> T getInstance0(Class<? extends T> clazz) throws Exception {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);

        if (constructor == null) {
            throw new NullPointerException("No default constructor is found from " + clazz.getName());
        }
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private AnnotatedObjectFactory() {}
}
