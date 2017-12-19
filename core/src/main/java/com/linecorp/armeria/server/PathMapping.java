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

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.linecorp.armeria.server.docs.DocService;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * Matches the absolute path part of a URI and extracts path parameters from it.
 */
public interface PathMapping {

    /**
     * Creates a new {@link PathMapping} that matches the specified {@code pathPattern}. e.g.
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
    static PathMapping of(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");

        if (pathPattern.startsWith(ExactPathMapping.PREFIX)) {
            return ofExact(pathPattern.substring(ExactPathMapping.PREFIX_LEN));
        }
        if (pathPattern.startsWith(PrefixPathMapping.PREFIX)) {
            return ofPrefix(pathPattern.substring(PrefixPathMapping.PREFIX_LEN));
        }
        if (pathPattern.startsWith(GlobPathMapping.PREFIX)) {
            return ofGlob(pathPattern.substring(GlobPathMapping.PREFIX_LEN));
        }
        if (pathPattern.startsWith(RegexPathMapping.PREFIX)) {
            return ofRegex(pathPattern.substring(RegexPathMapping.PREFIX_LEN));
        }
        if (!pathPattern.startsWith("/")) {
            throw new IllegalArgumentException(
                    "pathPattern: " + pathPattern + " (not an absolute path or a unknown pattern type)");
        }
        if (!pathPattern.contains("{") && !pathPattern.contains(":")) {
            return ofExact(pathPattern);
        }
        return new DefaultPathMapping(pathPattern);
    }

    /**
     * Creates a new {@link PathMapping} that matches a {@linkplain ServiceRequestContext#path() path} with
     * the specified regular expression and extracts the values of the named groups.
     * e.g. {@code "^/users/(?<userId>[0-9]+)$"} will extract the second numeric part of the path into
     *      the {@code "userId"} path parameter.
     */
    static PathMapping ofRegex(Pattern regex) {
        return new RegexPathMapping(regex);
    }

    /**
     * Creates a new {@link PathMapping} that matches a {@linkplain ServiceRequestContext#path() path} with
     * the specified regular expression and extracts the values of the named groups.
     * e.g. {@code "^/users/(?<userId>[0-9]+)$"} will extract the second numeric part of the path into
     *      the {@code "userId"} path parameter.
     */
    static PathMapping ofRegex(String regex) {
        return ofRegex(Pattern.compile(requireNonNull(regex, "regex")));
    }

    /**
     * Creates a new {@link PathMapping} that matches a {@linkplain ServiceRequestContext#path() path} with
     * the specified glob expression, where {@code "*"} matches a path component non-recursively and
     * {@code "**"} matches path components recursively.
     */
    static PathMapping ofGlob(String glob) {
        requireNonNull(glob, "glob");
        if (glob.startsWith("/") && !glob.contains("*")) {
            // Does not have a pattern matcher.
            return ofExact(glob);
        }

        return new GlobPathMapping(glob);
    }

    /**
     * Creates a new {@link PathMapping} that matches a {@linkplain ServiceRequestContext#path() path}
     * under the specified directory prefix. It also removes the specified directory prefix from the matched
     * path so that {@link ServiceRequestContext#path()} does not have the specified directory prefix.
     * For example, when {@code pathPrefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to  {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     * This method is a shortcut to {@link #ofPrefix(String, boolean) ofPrefix(pathPrefix, true)}.
     */
    static PathMapping ofPrefix(String pathPrefix) {
        return ofPrefix(pathPrefix, true);
    }

    /**
     * Creates a new {@link PathMapping} that matches a {@linkplain ServiceRequestContext#path() path}
     * under the specified directory prefix. When {@code stripPrefix} is {@code true}, it also removes the
     * specified directory prefix from the matched path so that {@link ServiceRequestContext#path()}
     * does not have the specified directory prefix. For example, when {@code pathPrefix} is {@code "/foo/"}:
     * <ul>
     *   <li>{@code "/foo/"} translates to {@code "/"}</li>
     *   <li>{@code "/foo/bar"} translates to  {@code "/bar"}</li>
     *   <li>{@code "/foo/bar/baz"} translates to {@code "/bar/baz"}</li>
     * </ul>
     */
    static PathMapping ofPrefix(String pathPrefix, boolean stripPrefix) {
        requireNonNull(pathPrefix, "pathPrefix");
        if ("/".equals(pathPrefix)) {
            // Every path starts with '/'.
            return ofCatchAll();
        }

        return new PrefixPathMapping(pathPrefix, stripPrefix);
    }

    /**
     * Creates a new {@link PathMapping} that performs an exact match.
     */
    static PathMapping ofExact(String exactPath) {
        return new ExactPathMapping(exactPath);
    }

    /**
     * Returns a singleton {@link PathMapping} that always matches any path.
     */
    static PathMapping ofCatchAll() {
        return CatchAllPathMapping.INSTANCE;
    }

    /**
     * Matches the specified {@code path} and extracts the path parameters from it.
     *
     * @param mappingCtx a context to find the {@link Service}.
     *
     * @return a non-empty {@link PathMappingResult} if the specified {@code path} matches this mapping.
     *         {@link PathMappingResult#empty()} if not matches.
     */
    PathMappingResult apply(PathMappingContext mappingCtx);

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
     * Returns the value of the {@code "pathMapping"} {@link Tag} in a {@link Meter}.
     */
    String meterTag();

    /**
     * Returns the exact path of this path mapping if it is an exact path mapping, or {@link Optional#empty}
     * otherwise. This can be useful for services which provide logic after scanning the server's mapped
     * services, e.g. {@link DocService}
     */
    Optional<String> exactPath();

    /**
     * Returns the prefix of this path mapping if it is a prefix mapping, or {@link Optional#empty}
     * otherwise. This can be useful for services which provide logic after scanning the server's mapped
     * services, e.g. {@link DocService}
     *
     * @return the prefix that ends with '/' if this mapping is a prefix mapping
     */
    Optional<String> prefix();

    /**
     * Returns the path of this path mapping if it is able to be added to a trie.
     */
    default Optional<String> triePath() {
        return Optional.empty();
    }

    /**
     * Returns the complexity of this path mapping. It would be increased if this path mapping has
     * more conditions to check.
     */
    default int complexity() {
        return 0;
    }
}
