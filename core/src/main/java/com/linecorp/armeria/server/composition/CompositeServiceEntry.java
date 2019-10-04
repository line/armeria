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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.Service;

/**
 * A pair of a {@link Route} and a {@link Service} bound to it.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class CompositeServiceEntry<I extends Request, O extends Response> {

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified regular expression.
     *
     * @see RouteBuilder#regex(Pattern)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofRegex(Pattern regex, Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().regex(regex).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified glob pattern.
     *
     * @see RouteBuilder#glob(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofGlob(String glob, Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().glob(glob).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound under the specified
     * directory.
     *
     * @see RouteBuilder#pathPrefix(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofPrefix(String pathPrefix, Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().pathPrefix(pathPrefix).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified exact path.
     *
     * @see RouteBuilder#exact(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofExact(String exactPath, Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().exact(exactPath).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at
     * {@linkplain RouteBuilder#catchAll() the catch-all path mapping}.
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofCatchAll(Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().catchAll().build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified path pattern.
     *
     * @see RouteBuilder#path(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> of(String pathPattern, Service<I, O> service) {
        return new CompositeServiceEntry<>(Route.builder().path(pathPattern).build(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} with the specified {@link Route} and {@link Service}.
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> of(Route route, Service<I, O> service) {
        return new CompositeServiceEntry<>(route, service);
    }

    private final Route route;
    private final Service<I, O> service;

    private CompositeServiceEntry(Route route, Service<I, O> service) {
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
    public Service<I, O> service() {
        return service;
    }

    @Override
    public String toString() {
        return route + " -> " + service;
    }
}
