/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * The default {@link PathMapping} implementation. It holds three things:
 * <ul>
 *   <li>The regex-compiled form of the path. It is used for matching and extracting.</li>
 *   <li>The skeleton of the path. It is used for duplication detecting.</li>
 *   <li>A set of path parameters declared in the path pattern</li>
 * </ul>
 */
final class DefaultPathMapping extends AbstractPathMapping {

    private static final Pattern VALID_PATTERN = Pattern.compile("(/[^/{}:]+|/:[^/{}]+|/\\{[^/{}]+})+/?");

    /**
     * The original path pattern specified in the constructor.
     */
    private final String pathPattern;

    /**
     * Regex form of given path, which will be used for matching or extracting.
     *
     * <p>e.g. "/{x}/{y}/{x}" -> "/(?&lt;x&gt;[^/]+)/(?&lt;y&gt;[^/]+)/(\\k&lt;x&gt;)"
     */
    private final Pattern pattern;

    /**
     * Skeletal form of given path, which is used for duplicated routing rule detection.
     * For example, "/{a}/{b}" and "/{c}/{d}" has same skeletal form and regarded as duplicated.
     *
     * <p>e.g. "/{x}/{y}/{z}" -> "/{}/{}/{}"
     */
    private final String skeleton;

    /**
     * The names of the path parameters this mapping will extract.
     */
    private final Set<String> paramNames;

    /**
     * Create a {@link DefaultPathMapping} instance from given {@code pathPattern}.
     *
     * @param pathPattern the {@link String} that contains path params.
     *             e.g. {@code /users/{name}} or {@code /users/:name}
     *
     * @throws IllegalArgumentException if the {@code pathPattern} is invalid.
     */
    DefaultPathMapping(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");

        if (!pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (must start with '/')");
        }

        if (!VALID_PATTERN.matcher(pathPattern).matches()) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (invalid pattern)");
        }

        StringJoiner patternJoiner = new StringJoiner("/");
        StringJoiner skeletonJoiner = new StringJoiner("/");
        Set<String> placeholders = new HashSet<>();
        for (String token : pathPattern.split("/")) {
            String paramName = paramName(token);
            if (paramName != null) {
                if (!placeholders.contains(paramName)) {
                    // If the given token appeared first time, add it to the set and
                    // replace it to named group expression in regex.
                    placeholders.add(paramName);
                    patternJoiner.add(String.format("(?<%s>[^/]+)", paramName));
                } else {
                    // If the given token appeared before, replace it with a back-reference expression
                    // in regex.
                    patternJoiner.add(String.format("\\k<%s>", paramName));
                }
                skeletonJoiner.add("{}");
            } else {
                // If the given token is a constant, does not manipulate it.
                patternJoiner.add(token);
                skeletonJoiner.add(token);
            }
        }

        this.pathPattern = pathPattern;
        pattern = Pattern.compile(patternJoiner.toString());
        skeleton = skeletonJoiner.toString();
        paramNames = ImmutableSet.copyOf(placeholders);
    }

    /**
     * Returns the name of the path parameter contained in the path element. If it contains no path parameter,
     * {@code null} is returned. e.g.
     * <ul>
     *   <li>{@code "{foo}"} -> {@code "foo"}</li>
     *   <li>{@code ":bar"} -> {@code "bar"}</li>
     *   <li>{@code "baz"} -> {@code null}</li>
     * </ul>
     */
    private static String paramName(String token) {
        if (token.startsWith("{") && token.endsWith("}")) {
            return token.substring(1, token.length() - 1);
        }

        if (token.startsWith(":")) {
            return token.substring(1);
        }

        return null;
    }

    /**
     * Returns the skeleton.
     */
    String skeleton() {
        return skeleton;
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    protected PathMappingResult doApply(String path, @Nullable String query) {
        final Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            return PathMappingResult.empty();
        }

        final Map<String, String> pathParams;
        if (paramNames.isEmpty()) {
            pathParams = ImmutableMap.of();
        } else {
            pathParams = paramNames.stream()
                                   .collect(toImmutableMap(Function.identity(), matcher::group));
        }

        return PathMappingResult.of(path, query, pathParams);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultPathMapping that = (DefaultPathMapping) o;

        return pattern.pattern().equals(that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return pattern.pattern().hashCode();
    }

    @Override
    public String toString() {
        return pathPattern;
    }
}
