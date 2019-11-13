/*
 * Copyright 2015 LINE Corporation
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

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * Matches the absolute path part of a URI and extracts path parameters from it.
 */
interface PathMapping {

    /**
     * Matches the specified {@code path} and extracts the path parameters from it.
     *
     * @param routingCtx a context to find the {@link HttpService}.
     *
     * @return a non-empty {@link RoutingResultBuilder} if the specified {@code path} matches this mapping.
     *         {@code null} otherwise.
     */
    @Nullable
    RoutingResultBuilder apply(RoutingContext routingCtx);

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
     * Returns the value of the {@link Tag} in a {@link Meter} of this {@link PathMapping}.
     */
    String meterTag();

    /**
     * Returns the type of the path which was specified when this is created.
     */
    RoutePathType pathType();

    /**
     * Returns the list of paths that this {@link Route} has. The paths are different according to the value
     * of {@link #pathType()}. If the path type has a {@linkplain RoutePathType#hasTriePath() trie path},
     * this method will return a two-element list whose first element is the path that represents the type and
     * the second element is the trie path. {@link RoutePathType#EXACT}, {@link RoutePathType#PREFIX} and
     * {@link RoutePathType#PARAMETERIZED} have the trie path.
     *
     * <ul>
     *   <li>EXACT: {@code [ "/foo", "/foo" ]} (The trie path is the same.)</li>
     *   <li>PREFIX: {@code [ "/foo/", "/foo/*" ]}</li>
     *   <li>PARAMETERIZED: {@code [ "/foo/:", "/foo/:" ]} (The trie path is the same.)</li>
     * </ul>
     *
     * {@link RoutePathType#REGEX} has only one path that represents it. e.g, {@code [ "^/(?<foo>.*)$" ]}
     *
     * <p>{@link RoutePathType#REGEX_WITH_PREFIX} has two paths. The first one is the prefix and the second
     * one is the regex. e.g, {@code [ "/bar/", "^/(?<foo>.*)$" ]}
     */
    List<String> paths();
}
