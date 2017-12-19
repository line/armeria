/*
 * Copyright 2017 LINE Corporation
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * <p>e.g. "/{x}/{y}/{z}" -> "/:/:/:"
     */
    private final Optional<String> skeleton;

    /**
     * The names of the path parameters in the order of appearance.
     */
    private final String[] paramNameArray;

    /**
     * The names of the path parameters this mapping will extract.
     */
    private final Set<String> paramNames;

    private final String loggerName;

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

        final StringJoiner patternJoiner = new StringJoiner("/");
        final StringJoiner skeletonJoiner = new StringJoiner("/");
        final List<String> paramNames = new ArrayList<>();
        for (String token : pathPattern.split("/")) {
            final String paramName = paramName(token);
            if (paramName == null) {
                // If the given token is a constant, do not manipulate it.
                patternJoiner.add(token);
                skeletonJoiner.add(token);
                continue;
            }

            final int paramNameIdx = paramNames.indexOf(paramName);
            if (paramNameIdx < 0) {
                // If the given token appeared first time, add it to the set and
                // replace it with a capturing group expression in regex.
                paramNames.add(paramName);
                patternJoiner.add("([^/]+)");
            } else {
                // If the given token appeared before, replace it with a back-reference expression
                // in regex.
                patternJoiner.add("\\" + (paramNameIdx + 1));
            }
            skeletonJoiner.add(":");
        }

        this.pathPattern = pathPattern;
        pattern = Pattern.compile(patternJoiner.toString());
        skeleton = Optional.of(skeletonJoiner.toString());
        paramNameArray = paramNames.toArray(new String[paramNames.size()]);
        this.paramNames = ImmutableSet.copyOf(paramNames);

        loggerName = loggerName(pathPattern);
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
        return skeleton.get();
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String meterTag() {
        return pathPattern;
    }

    @Override
    public Optional<String> triePath() {
        return skeleton;
    }

    @Override
    protected PathMappingResult doApply(PathMappingContext mappingCtx) {
        final Matcher matcher = pattern.matcher(mappingCtx.path());
        if (!matcher.matches()) {
            return PathMappingResult.empty();
        }

        if (paramNameArray.length == 0) {
            return PathMappingResult.of(mappingCtx.path(), mappingCtx.query());
        }

        final ImmutableMap.Builder<String, String> pathParams = ImmutableMap.builder();
        for (int i = 0; i < paramNameArray.length; i++) {
            pathParams.put(paramNameArray[i], matcher.group(i + 1));
        }
        return PathMappingResult.of(mappingCtx.path(), mappingCtx.query(), pathParams.build());
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

        return skeleton.equals(that.skeleton) &&
               Arrays.equals(paramNameArray, that.paramNameArray);
    }

    @Override
    public int hashCode() {
        return skeleton.hashCode() * 31 + Arrays.hashCode(paramNameArray);
    }

    @Override
    public String toString() {
        return pathPattern;
    }
}
