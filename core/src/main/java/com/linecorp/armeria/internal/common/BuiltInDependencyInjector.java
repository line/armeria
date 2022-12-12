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
package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.ReflectiveDependencyInjector.create;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.server.annotation.ServerSentEventResponseConverterFunction;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.RateLimitingDecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.RequestTimeoutDecoratorFunction;

public enum BuiltInDependencyInjector implements DependencyInjector {

    INSTANCE;

    //TODO(minwoox): Consider organizing built in class in a package and use reflection.
    private static final Set<Class<?>> builtInClasses =
            ImmutableSet.of(LoggingDecoratorFactoryFunction.class,
                            RateLimitingDecoratorFactoryFunction.class,
                            ServerSentEventResponseConverterFunction.class,
                            RequestTimeoutDecoratorFunction.class);

    private static final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();

    @Override
    public <T> T getInstance(Class<T> type) {
        if (!builtInClasses.contains(type)) {
            return null;
        }

        //noinspection unchecked
        return (T) instances.computeIfAbsent(type, key -> {
            final Object instance = create(key, null);
            assert instance != null;
            return instance;
        });
    }

    @Override
    public void close() {
        // No need to close.
    }
}
