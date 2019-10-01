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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.RouteUtil.EXACT;
import static com.linecorp.armeria.internal.RouteUtil.GLOB;
import static com.linecorp.armeria.internal.RouteUtil.PREFIX;
import static com.linecorp.armeria.internal.RouteUtil.REGEX;
import static com.linecorp.armeria.internal.RouteUtil.ensureAbsolutePath;
import static com.linecorp.armeria.server.HttpHeaderUtil.ensureUniqueMediaTypes;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;

/**
 * Builds a new {@link Route}.
 */
public class RouteBuilder {

    @Nullable
    private PathMapping pathMapping;

    private Set<HttpMethod> methods = ImmutableSet.of();

    private Set<MediaType> consumes = ImmutableSet.of();

    private Set<MediaType> produces = ImmutableSet.of();

    /**
     * Sets the {@link Route} to match the specified {@code pathPattern}. e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     *   <li>{@code exact:/foo/bar} (exact match)</li>
     *   <li>{@code prefix:/files} (prefix match)</li>
     *   <li><code>glob:/~&#42;/downloads/**</code> (glob pattern)</li>
     *   <li>{@code regex:^/files/(?<filePath>.*)$} (regular expression)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public RouteBuilder path(String pathPattern) {
        return pathMapping(getPathMapping(pathPattern));
    }

    /**
     * Sets the {@link Route} to match the specified {@code prefix} and {@code pathPattern}. The mapped
     * {@link Service} is found when a {@linkplain ServiceRequestContext#path() path} is under
     * the specified {@code prefix} and the rest of the path matches the specified {@code pathPattern}.
     *
     * @see #path(String)
     */
    public RouteBuilder path(String prefix, String pathPattern) {
        prefix = ensureAbsolutePath(prefix, "prefix");
        if (!prefix.endsWith("/")) {
            prefix += '/';
        }

        if ("/".equals(prefix)) {
            // pathPrefix is not specified or "/".
            return path(pathPattern);
        }

        if (pathPattern.startsWith("/")) {
            return path(concatPaths(prefix, pathPattern));
        }

        if (pathPattern.startsWith(EXACT)) {
            return exact(concatPaths(prefix, pathPattern.substring(EXACT.length())));
        }

        if (pathPattern.startsWith(PREFIX)) {
            return pathPrefix(concatPaths(prefix, pathPattern.substring(PREFIX.length())));
        }

        if (pathPattern.startsWith(GLOB)) {
            final String glob = pathPattern.substring(GLOB.length());
            if (glob.startsWith("/")) {
                return glob(concatPaths(prefix, glob));
            } else {
                return glob(concatPaths(prefix + "**/", glob), 1);
            }
        }

        return pathMapping(new RegexPathMappingWithPrefix(prefix, getPathMapping(pathPattern)));
    }

    private static PathMapping globPathMapping(String glob, int numGroupsToSkip) {
        if (glob.startsWith("/") && !glob.contains("*")) {
            // Does not have a pattern matcher.
            return new ExactPathMapping(glob);
        }

        return new GlobPathMapping(glob, numGroupsToSkip);
    }

    RouteBuilder pathMapping(PathMapping pathMapping) {
        this.pathMapping = pathMapping;
        return this;
    }

    /**
     * Sets the {@link Route} to match the specified exact path.
     */
    public RouteBuilder exact(String exactPath) {
        return pathMapping(new ExactPathMapping(exactPath));
    }

    /**
     * Sets the {@link Route} to match when a {@linkplain ServiceRequestContext#path() path} is under the
     * specified {@code prefix}. It also removes the specified {@code prefix} from the matched path so that
     * {@linkplain ServiceRequestContext#mappedPath() mappedPath} does not have the specified {@code prefix}.
     * For example, when {@code prefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to  {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     * This method is a shortcut to {@linkplain #pathPrefix(String, boolean) pathPrefix(prefix, true)}.
     *
     * @deprecated Use {@link #pathPrefix(String)}
     */
    @Deprecated
    public RouteBuilder prefix(String prefix) {
        return pathPrefix(prefix, true);
    }

    /**
     * Sets the {@link Route} to match when a {@linkplain ServiceRequestContext#path() path} is under the
     * specified {@code prefix}. When {@code stripPrefix} is {@code true}, it also removes the specified
     * {@code prefix} from the matched path so that {@linkplain ServiceRequestContext#path() mappedPath}
     * does not have the specified {@code prefix}. For example, when {@code prefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to  {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     *
     * @deprecated Use {@link #pathPrefix(String, boolean)}
     */
    @Deprecated
    public RouteBuilder prefix(String prefix, boolean stripPrefix) {
        return pathPrefix(prefix, stripPrefix);
    }

    /**
     * Sets the {@link Route} to match when a {@linkplain ServiceRequestContext#path() path} is under the
     * specified {@code prefix}. It also removes the specified {@code prefix} from the matched path so that
     * {@linkplain ServiceRequestContext#mappedPath() mappedPath} does not have the specified {@code prefix}.
     * For example, when {@code prefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     * This method is a shortcut to {@linkplain #pathPrefix(String, boolean) pathPrefix(prefix, true)}.
     */
    public RouteBuilder pathPrefix(String prefix) {
        return pathPrefix(prefix, true);
    }

    /**
     * Sets the {@link Route} to match when a {@linkplain ServiceRequestContext#path() path} is under the
     * specified {@code prefix}. When {@code stripPrefix} is {@code true}, it also removes the specified
     * {@code prefix} from the matched path so that {@linkplain ServiceRequestContext#path() mappedPath}
     * does not have the specified {@code prefix}. For example, when {@code prefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     */
    public RouteBuilder pathPrefix(String prefix, boolean stripPrefix) {
        return pathMapping(prefixPathMapping(requireNonNull(prefix, "prefix"), stripPrefix));
    }

    /**
     * Sets the {@link Route} to match any path.
     */
    public RouteBuilder catchAll() {
        return pathMapping(CatchAllPathMapping.INSTANCE);
    }

    /**
     * Sets the {@link Route} to match the specified {@code glob}.
     * {@code "*"} in the expression matches a path component non-recursively whereas {@code "**"} matches
     * path components recursively.
     */
    public RouteBuilder glob(String glob) {
        return glob(glob, 0);
    }

    private RouteBuilder glob(String glob, int numGroupsToSkip) {
        return pathMapping(globPathMapping(requireNonNull(glob, "glob"), numGroupsToSkip));
    }

    /**
     * Sets the {@link Route} to match the specified {@code regex}. It also extracts
     * the values of the named groups to {@linkplain ServiceRequestContext#pathParams() pathParams}:
     * e.g. {@code "^/users/(?<userId>[0-9]+)$"} will extract the second numeric part of the path into
     * the {@code "userId"} path parameter.
     */
    public RouteBuilder regex(String regex) {
        return regex(Pattern.compile(requireNonNull(regex, "regex")));
    }

    /**
     * Sets the {@link Route} to match the specified {@code regex}. It also extracts
     * the values of the named groups to {@linkplain ServiceRequestContext#pathParams() pathParams}:
     * e.g. {@code "^/users/(?<userId>[0-9]+)$"} will extract the second numeric part of the path into
     * the {@code "userId"} path parameter.
     */
    public RouteBuilder regex(Pattern regex) {
        return pathMapping(new RegexPathMapping(regex));
    }

    /**
     * Sets the {@link Route} to match the specified {@code prefix} and {@code pathPattern}. The mapped
     * {@link Service} is found when a {@linkplain ServiceRequestContext#path() path} is under
     * the specified {@code prefix} and the rest of the path matches the specified {@code pathPattern}.
     *
     * @deprecated Use {@linkplain #path(String, String) path(prefix, pathPattern)}
     */
    @Deprecated
    public RouteBuilder pathWithPrefix(String prefix, String pathPattern) {
        return path(prefix, pathPattern);
    }

    /**
     * Sets the {@link Route} to support the specified {@link HttpMethod}s. If not set,
     * the mapped {@link Service} accepts any {@link HttpMethod}s.
     */
    public RouteBuilder methods(HttpMethod... methods) {
        methods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
        return this;
    }

    /**
     * Sets the {@link Route} to support the specified {@link HttpMethod}s. If not set,
     * the mapped {@link Service} accepts any {@link HttpMethod}s.
     */
    public RouteBuilder methods(Iterable<HttpMethod> methods) {
        this.methods = Sets.immutableEnumSet(requireNonNull(methods, "methods"));
        return this;
    }

    /**
     * Sets the {@link Route} to consume the specified {@link MediaType}s. If not set,
     * the mapped {@link Service} accepts {@link HttpRequest}s that have any
     * {@link HttpHeaderNames#CONTENT_TYPE}. In order to get this work, {@link #methods(Iterable)} must be set.
     */
    public RouteBuilder consumes(MediaType... consumeTypes) {
        consumes(ImmutableSet.copyOf(requireNonNull(consumeTypes, "consumeTypes")));
        return this;
    }

    /**
     * Sets the {@link Route} to consume the specified {@link MediaType}s. If not set,
     * the mapped {@link Service} accepts {@link HttpRequest}s that have any
     * {@link HttpHeaderNames#CONTENT_TYPE}. In order to get this work, {@link #methods(Iterable)} must be set.
     */
    public RouteBuilder consumes(Iterable<MediaType> consumeTypes) {
        ensureUniqueMediaTypes(consumeTypes, "consumeTypes");
        consumes = ImmutableSet.copyOf(consumeTypes);
        return this;
    }

    /**
     * Sets the {@link Route} to produce the specified {@link MediaType}s. If not set,
     * the mapped {@link Service} accepts {@link HttpRequest}s that have any
     * {@link HttpHeaderNames#ACCEPT}. In order to get this work, {@link #methods(Iterable)} must be set.
     */
    public RouteBuilder produces(MediaType... produceTypes) {
        produces(ImmutableSet.copyOf(requireNonNull(produceTypes, "produceTypes")));
        return this;
    }

    /**
     * Sets the {@link Route} to produce the specified {@link MediaType}s. If not set,
     * the mapped {@link Service} accepts {@link HttpRequest}s that have any
     * {@link HttpHeaderNames#ACCEPT}. In order to get this work, {@link #methods(Iterable)} must be set.
     */
    public RouteBuilder produces(Iterable<MediaType> produceTypes) {
        ensureUniqueMediaTypes(produceTypes, "produceTypes");
        produces = ImmutableSet.copyOf(produceTypes);
        return this;
    }

    /**
     * Returns a newly-created {@link Route} based on the properties of this builder.
     */
    public Route build() {
        checkState(pathMapping != null, "Must set a path before calling this.");
        if ((!consumes.isEmpty() || !produces.isEmpty()) && methods.isEmpty()) {
            throw new IllegalStateException("Must set methods if consumes or produces is not empty." +
                                            " consumes: " + consumes + ", produces: " + produces);
        }
        return new DefaultRoute(pathMapping, methods, consumes, produces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathMapping, methods, consumes, produces);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RouteBuilder)) {
            return false;
        }

        final RouteBuilder that = (RouteBuilder) o;
        return Objects.equals(pathMapping, that.pathMapping) &&
               methods.equals(that.methods) &&
               consumes.equals(that.consumes) &&
               produces.equals(that.produces);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("pathMapping", pathMapping)
                          .add("methods", methods)
                          .add("consumes", consumes)
                          .add("produces", produces)
                          .toString();
    }

    private static PathMapping getPathMapping(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");

        if (pathPattern.startsWith(EXACT)) {
            return new ExactPathMapping(pathPattern.substring(EXACT.length()));
        }
        if (pathPattern.startsWith(PREFIX)) {
            final String prefix = pathPattern.substring(PREFIX.length());
            return prefixPathMapping(prefix, true);
        }
        if (pathPattern.startsWith(GLOB)) {
            final String glob = pathPattern.substring(GLOB.length());
            return globPathMapping(glob, 0);
        }
        if (pathPattern.startsWith(REGEX)) {
            return new RegexPathMapping(Pattern.compile(pathPattern.substring(REGEX.length())));
        }
        if (!pathPattern.startsWith("/")) {
            throw new IllegalArgumentException(
                    "pathPattern: " + pathPattern + " (not an absolute path or a unknown pattern type)");
        }
        if (!pathPattern.contains("{") && !pathPattern.contains(":")) {
            return new ExactPathMapping(pathPattern);
        }
        return new ParameterizedPathMapping(pathPattern);
    }

    private static PathMapping prefixPathMapping(String prefix, boolean stripPrefix) {
        if ("/".equals(prefix)) {
            // Every path starts with '/'.
            return CatchAllPathMapping.INSTANCE;
        }

        return new PrefixPathMapping(prefix, stripPrefix);
    }
}
