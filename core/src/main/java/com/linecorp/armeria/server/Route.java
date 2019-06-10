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

package com.linecorp.armeria.server;

import java.util.Optional;
import java.util.Set;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * {@link Route} maps from an incoming HTTP request to a {@link Service} based on its path, method,
 * content type and accepted types.
 */
public interface Route {

    /**
     * Returns a new builder.
     */
    static RouteBuilder builder() {
        return new RouteBuilder();
    }

    /**
     * Matches the specified {@link RoutingContext} and extracts the path parameters from it if exists.
     *
     * @param routingCtx a context to find the {@link Service}
     *
     * @return a non-empty {@link RoutingResult} if the {@linkplain RoutingContext#path() path},
     *         {@linkplain RoutingContext#method() method},
     *         {@linkplain RoutingContext#contentType() contentType} and
     *         {@linkplain RoutingContext#acceptTypes() acceptTypes} matches the equivalent conditions in
     *         {@link Route}. {@link RoutingResult#empty()} otherwise.
     *
     * @see RouteBuilder#methods(Iterable)
     * @see RouteBuilder#consumes(Iterable)
     * @see RouteBuilder#produces(Iterable)
     */
    RoutingResult apply(RoutingContext routingCtx);

    /**
     * Returns the names of the path parameters extracted by this mapping.
     */
    Set<String> paramNames();

    /**
     * Returns the logger name.
     *
     * @return the logger name whose components are separated by a dot (.)
     */
    String loggerName();

    /**
     * Returns the value of the {@link Tag} in a {@link Meter} of this {@link Route}.
     */
    String meterTag();

    /**
     * Returns the exact path of this {@link Route} if this has an exact path matching condition,
     * {@link Optional#empty()} otherwise.
     */
    Optional<String> exactPath();

    /**
     * Returns the prefix of this {@link Route} if this has a prefix matching condition,
     * {@link Optional#empty()} otherwise.
     *
     * @return the prefix that ends with '/' if this {@link Route} has a prefix matching condition
     */
    Optional<String> prefix();

    /**
     * Returns the trie path of this {@link Route} if this has a trie matching condition,
     * {@link Optional#empty()} otherwise. The trie path does not exist when the {@link Route} is created
     * using a glob or a regex expression.
     *
     * @see RouteBuilder#path(String)
     * @see RouteBuilder#glob(String)
     * @see RouteBuilder#regex(String)
     */
    Optional<String> triePath();

    /**
     * Returns the regular expression of this {@link Route} if this is created with the regular expression or
     * glob pattern, {@link Optional#empty()} otherwise. Please note that this regex does not include the
     * {@code pathPrefix} if this is created with {@link RouteBuilder#pathWithPrefix(String, String)}.
     * You should call {@link #prefix()} to retrieve that.
     */
    Optional<String> regex();

    /**
     * Returns the complexity of this {@link Route}. A higher complexity indicates more expensive computation
     * for route matching, usually due to additional number of checks.
     */
    int complexity();

    /**
     * Returns the {@link Set} of {@link HttpMethod}s that this {@link Route} supports.
     */
    Set<HttpMethod> methods();

    /**
     * Returns the {@link Set} of {@link MediaType}s that this {@link Route} consumes.
     */
    Set<MediaType> consumes();

    /**
     * Returns the {@link Set} of {@link MediaType}s that this {@link Route} produces.
     */
    Set<MediaType> produces();
}
