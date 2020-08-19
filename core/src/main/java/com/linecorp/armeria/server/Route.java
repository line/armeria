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

import java.util.List;
import java.util.Set;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

/**
 * {@link Route} maps from an incoming HTTP request to an {@link HttpService} based on its path, method,
 * content type and accepted types.
 */
public interface Route {

    /**
     * Returns the catch-all {@link Route} which matches every request.
     */
    static Route ofCatchAll() {
        return RouteBuilder.CATCH_ALL_ROUTE;
    }

    /**
     * Returns a new builder.
     */
    static RouteBuilder builder() {
        return new RouteBuilder();
    }

    /**
     * Matches the specified {@link RoutingContext} and extracts the path parameters from it if exists.
     *
     * @param routingCtx a context to find the {@link HttpService}
     *
     * @return a non-empty {@link RoutingResult} if the {@linkplain RoutingContext#path() path},
     *         {@linkplain RoutingContext#method() method},
     *         {@linkplain RoutingContext#contentType() contentType} and
     *         {@linkplain RoutingContext#acceptTypes() acceptTypes} and
     *         {@linkplain RoutingContext#headers() HTTP headers} and
     *         {@linkplain RoutingContext#params() query parameters} matches the equivalent conditions in
     *         {@link Route}. {@link RoutingResult#empty()} otherwise.
     *
     * @see RouteBuilder#methods(Iterable)
     * @see RouteBuilder#consumes(Iterable)
     * @see RouteBuilder#produces(Iterable)
     * @see RouteBuilder#matchesHeaders(Iterable)
     * @see RouteBuilder#matchesParams(Iterable)
     */
    RoutingResult apply(RoutingContext routingCtx);

    /**
     * Returns the names of the path parameters extracted by this mapping.
     */
    Set<String> paramNames();

    /**
     * Returns the path pattern of this {@link Route}. The returned path pattern is different according to
     * the value of {@link #pathType()}.
     *
     * <ul>
     *   <li>{@linkplain RoutePathType#EXACT EXACT}: {@code "/foo"} or {@code "/foo/bar"}</li>
     *   <li>{@linkplain RoutePathType#PREFIX PREFIX}: {@code "/foo/*"}</li>
     *   <li>{@linkplain RoutePathType#PARAMETERIZED PARAMETERIZED}: {@code "/foo/:bar"} or
     *       {@code "/foo/:bar/:qux}</li>
     *   <li>{@linkplain RoutePathType#REGEX REGEX} may have a glob pattern or a regular expression:
     *     <ul>
     *       <li><code>"/*&#42;/foo"</code> if the {@link Route} was created using
     *           {@link RouteBuilder#glob(String)}</li>
     *       <li>{@code "^/(?(.+)/)?foo$"} if the {@link Route} was created using
     *           {@link RouteBuilder#regex(String)}</li>
     *     </ul>
     *   </li>
     *   <li>{@linkplain RoutePathType#REGEX_WITH_PREFIX REGEX_WITH_PREFIX} may have a glob pattern or
     *       a regular expression with a prefix:
     *     <ul>
     *       <li>{@code "/foo/bar/**"} if the {@link Route} was created using
     *           {@code RouteBuilder.path("/foo/", "glob:/bar/**")}</li>
     *       <li>{@code "/foo/(bar|baz)"} if the {@link Route} was created using
     *           {@code RouteBuilder.path("/foo/", "regex:/(bar|baz)")}</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    String patternString();

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
     * <p>{@link RoutePathType#REGEX} may have one or two paths. If the {@link Route} was created from a glob
     * pattern, it will have two paths where the first one is the regular expression and the second one
     * is the glob pattern, e.g. <code>[ "^/(?(.+)/)?foo$", "/*&#42;/foo" ]</code>.
     * If not created from a glob pattern, it will have only one path, which is the regular expression,
     * e.g, {@code [ "^/(?<foo>.*)$" ]}</p>
     *
     * <p>{@link RoutePathType#REGEX_WITH_PREFIX} has two paths. The first one is the regex and the second
     * one is the path. e.g, {@code [ "^/(?<foo>.*)$", "/bar/" ]}
     */
    List<String> paths();

    /**
     * Returns the complexity of this {@link Route}. A higher complexity indicates more expensive computation
     * for route matching, usually due to additional number of checks.
     */
    int complexity();

    /**
     * Returns the {@link Set} of non-empty {@link HttpMethod}s that this {@link Route} supports.
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
