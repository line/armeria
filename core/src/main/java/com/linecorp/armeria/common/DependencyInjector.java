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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.ResponseConverter;

/**
 * Injects dependencies that are specified in {@link RequestConverter#value()},
 * {@link ResponseConverter#value()}, {@link ExceptionHandler#value()}, {@link Decorator#value()} and
 * {@link DecoratorFactory#value()}.
 * If the dependencies are not injected by this {@link DependencyInjector}, they are created via the default
 * constructor, which does not have a parameter, of the classes.
 */
@UnstableApi
public interface DependencyInjector extends SafeCloseable {

    /**
     * Returns a {@link DependencyInjector} that injects dependencies using the specified singleton instances.
     * The instances are {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@link Server} stops.
     */
    static DependencyInjector ofSingletons(Object... singletons) {
        return ofSingletons(ImmutableList.copyOf(requireNonNull(singletons, "singletons")));
    }

    /**
     * Returns a {@link DependencyInjector} that injects dependencies using the specified singleton instances.
     * The instances are {@linkplain AutoCloseable#close() closed} if it implements {@link AutoCloseable}
     * when the {@link Server} stops.
     */
    static DependencyInjector ofSingletons(Iterable<Object> singletons) {
        return new DefaultDependencyInjector(singletons);
    }

    /**
     * Returns the instance of the specified {@link Class}.
     */
    @Nullable
    <T> T getInstance(Class<T> type);

    /**
     * Returns a new {@link DependencyInjector} that tries {@link #getInstance(Class)} of
     * this {@link DependencyInjector} first and the specified {@link DependencyInjector}.
     */
    default DependencyInjector orElse(DependencyInjector dependencyInjector) {
        requireNonNull(dependencyInjector, "dependencyInjector");
        return new OrElseDependencyInjector(this, dependencyInjector);
    }
}
