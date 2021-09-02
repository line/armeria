/*
 * Copyright 2019 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.AnnotatedBeanFactoryRegistry.BeanFactoryId;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.ResolverContext;

final class AnnotatedBeanFactory<T> {

    private final BeanFactoryId beanFactoryId;
    private final Entry<Constructor<T>, List<AnnotatedValueResolver>> constructor;
    private final Map<Field, AnnotatedValueResolver> fields;
    private final Map<Method, List<AnnotatedValueResolver>> methods;

    AnnotatedBeanFactory(BeanFactoryId beanFactoryId,
                         Entry<Constructor<T>, List<AnnotatedValueResolver>> constructor,
                         Map<Method, List<AnnotatedValueResolver>> methods,
                         Map<Field, AnnotatedValueResolver> fields) {
        this.beanFactoryId = requireNonNull(beanFactoryId, "beanFactoryId");
        this.constructor = immutableEntry(requireNonNull(constructor, "constructor"));
        this.fields = ImmutableMap.copyOf(requireNonNull(fields, "fields"));
        this.methods = ImmutableMap.copyOf(requireNonNull(methods, "methods"));
    }

    private static <K, V> Entry<K, V> immutableEntry(Entry<K, V> entry) {
        if (entry instanceof AbstractMap.SimpleImmutableEntry) {
            return entry;
        }
        return new SimpleImmutableEntry<>(entry);
    }

    T create(ResolverContext resolverContext) {
        try {
            final Object[] constructorArgs = AnnotatedValueResolver.toArguments(
                    constructor.getValue(), resolverContext);
            final T instance = constructor.getKey().newInstance(constructorArgs);

            for (final Entry<Method, List<AnnotatedValueResolver>> method : methods.entrySet()) {
                final Object[] methodArgs = AnnotatedValueResolver.toArguments(
                        method.getValue(), resolverContext);
                method.getKey().invoke(instance, methodArgs);
            }

            for (final Entry<Field, AnnotatedValueResolver> field : fields.entrySet()) {
                @Nullable
                final Object fieldArg = field.getValue().resolve(resolverContext);
                field.getKey().set(instance, fieldArg);
            }

            return instance;
        } catch (Throwable cause) {
            throw new IllegalArgumentException(
                    "cannot instantiate a new object: " + beanFactoryId, cause);
        }
    }

    Entry<Constructor<T>, List<AnnotatedValueResolver>> constructor() {
        return constructor;
    }

    Map<Method, List<AnnotatedValueResolver>> methods() {
        return methods;
    }

    Map<Field, AnnotatedValueResolver> fields() {
        return fields;
    }
}
