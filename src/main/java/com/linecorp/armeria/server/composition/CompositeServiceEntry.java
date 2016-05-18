/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A pair of a {@link PathMapping} and a {@link Service} bound to it.
 */
public final class CompositeServiceEntry {

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified regular expression.
     *
     * @see PathMapping#ofRegex(Pattern)
     */
    public static CompositeServiceEntry ofRegex(Pattern regex, Service service) {
        return new CompositeServiceEntry(PathMapping.ofRegex(regex), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the path that matches
     * the specified glob pattern.
     *
     * @see PathMapping#ofGlob(String)
     */
    public static CompositeServiceEntry ofGlob(String glob, Service service) {
        return new CompositeServiceEntry(PathMapping.ofGlob(glob), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound under the specified
     * directory.
     *
     * @see PathMapping#ofPrefix(String)
     */
    public static CompositeServiceEntry ofPrefix(String pathPrefix, Service service) {
        return new CompositeServiceEntry(PathMapping.ofPrefix(pathPrefix), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound under the specified
     * directory.
     *
     * @see PathMapping#ofPrefix(String, boolean)
     */
    public static CompositeServiceEntry ofPrefix(
            String pathPrefix, Service service, boolean stripPrefix) {
        return new CompositeServiceEntry(PathMapping.ofPrefix(pathPrefix, stripPrefix), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at the specified exact path.
     *
     * @see PathMapping#ofExact(String)
     */
    public static CompositeServiceEntry ofExact(String exactPath, Service service) {
        return new CompositeServiceEntry(PathMapping.ofExact(exactPath), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} whose {@link Service} is bound at
     * {@linkplain PathMapping#ofCatchAll() the catch-all path mapping}.
     */
    public static CompositeServiceEntry ofCatchAll(Service service) {
        return new CompositeServiceEntry(PathMapping.ofCatchAll(), service);
    }

    /**
     * Creates a new {@link CompositeServiceEntry} with the specified {@link PathMapping} and {@link Service}.
     */
    public static CompositeServiceEntry of(PathMapping pathMapping, Service service) {
        return new CompositeServiceEntry(pathMapping, service);
    }

    private final PathMapping pathMapping;
    private final Service service;

    private CompositeServiceEntry(PathMapping pathMapping, Service service) {
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
    public Service service() {
        return service;
    }

    @Override
    public String toString() {
        return pathMapping + " -> " + service;
    }
}
