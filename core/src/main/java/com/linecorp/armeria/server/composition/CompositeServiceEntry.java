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
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A pair of a {@link PathMapping} and a {@link Service} bound to it.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class CompositeServiceEntry<I extends Request, O extends Response> {

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified regular expression.
     *
     * @see PathMapping#ofRegex(Pattern)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofRegex(Pattern regex, Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.ofRegex(regex), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified glob pattern.
     *
     * @see PathMapping#ofGlob(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofGlob(String glob, Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.ofGlob(glob), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound under the specified
     * directory.
     *
     * @see PathMapping#ofPrefix(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofPrefix(String pathPrefix, Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.ofPrefix(pathPrefix), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified exact path.
     *
     * @see PathMapping#ofExact(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofExact(String exactPath, Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.ofExact(exactPath), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at
     * {@linkplain PathMapping#ofCatchAll() the catch-all path mapping}.
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> ofCatchAll(Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.ofCatchAll(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified path pattern.
     *
     * @see PathMapping#of(String)
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> of(String pathPattern, Service<I, O> service) {
        return new CompositeServiceEntry<>(PathMapping.of(pathPattern), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} with the specified {@link PathMapping} and {@link Service}.
     */
    public static <I extends Request, O extends Response>
    CompositeServiceEntry<I, O> of(PathMapping pathMapping, Service<I, O> service) {
        return new CompositeServiceEntry<>(pathMapping, service);
    }

    private final PathMapping pathMapping;
    private final Service<I, O> service;

    private CompositeServiceEntry(PathMapping pathMapping, Service<I, O> service) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.service = requireNonNull(service, "service");
    }

    /**
     * Returns the {@link PathMapping} of the {@link #service()}.
     */
    public PathMapping pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the {@link Service}.
     */
    public Service<I, O> service() {
        return service;
    }

    @Override
    public String toString() {
        return pathMapping + " -> " + service;
    }
}
