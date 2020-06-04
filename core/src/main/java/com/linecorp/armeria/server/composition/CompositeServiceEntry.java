/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.composition;

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.Service;

/**
 * A pair of a {@link Route} and an {@link Service} bound to it.
 *
 * @param <T> the {@link Service} type
 *
 * @deprecated This class will be removed without a replacement.
 */
@Deprecated
public final class CompositeServiceEntry<T extends Service<?, ?>> {

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified regular expression.
     *
     * @see RouteBuilder#regex(Pattern)
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> ofRegex(Pattern regex, T service) {
        return new CompositeServiceEntry<>(Route.builder().regex(regex).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified glob pattern.
     *
     * @see RouteBuilder#glob(String)
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> ofGlob(String glob, T service) {
        return new CompositeServiceEntry<>(Route.builder().glob(glob).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound under the specified
     * directory.
     *
     * @see RouteBuilder#pathPrefix(String)
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> ofPrefix(String pathPrefix, T service) {
        return new CompositeServiceEntry<>(Route.builder().pathPrefix(pathPrefix).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified
     * exact path.
     *
     * @see RouteBuilder#exact(String)
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> ofExact(String exactPath, T service) {
        return new CompositeServiceEntry<>(Route.builder().exact(exactPath).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at
     * {@linkplain Route#ofCatchAll() the catch-all path mapping}.
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> ofCatchAll(T service) {
        return new CompositeServiceEntry<>(Route.ofCatchAll(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified
     * path pattern.
     *
     * @see RouteBuilder#path(String)
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> of(String pathPattern, T service) {
        return new CompositeServiceEntry<>(Route.builder().path(pathPattern).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} with the specified {@link Route} and {@link Service}.
     */
    public static <T extends Service<?, ?>> CompositeServiceEntry<T> of(Route route, T service) {
        return new CompositeServiceEntry<>(route, service);
    }

    private final Route route;
    private final T service;

    private CompositeServiceEntry(Route route, T service) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
    }

    /**
     * Returns the {@link Route} of the {@link #service()}.
     */
    public Route route() {
        return route;
    }

    /**
     * Returns the {@link Service}.
     */
    public T service() {
        return service;
    }

    @Override
    public String toString() {
        return route + " -> " + service;
    }
}
