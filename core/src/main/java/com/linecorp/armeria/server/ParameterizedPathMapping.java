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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * The default {@link PathMapping} implementation. It holds three things:
 * <ul>
 *   <li>The regex-compiled form of the path. It is used for matching and extracting.</li>
 *   <li>The skeleton of the path. It is used for duplication detecting.</li>
 *   <li>A set of path parameters declared in the path pattern</li>
 * </ul>
 */
final class ParameterizedPathMapping extends AbstractPathMapping {

    private static final Pattern VALID_PATTERN = Pattern.compile(
            '(' +
            // If the segment doesn't start with ':' or '{', the behavior should be the same as ExactPathMapping
            "/[^:{][^/]*|" +
            "/:[^/{}]+|" +
            "/\\{[^/{}]+}" +
            ")+/?"
    );

    private static final Pattern CAPTURE_REST_PATTERN = Pattern.compile("/\\{\\*([^/{}]*)}|/:\\*([^/{}]*)");

    private static final Pattern CAPTURE_REST_VARIABLE_NAME_PATTERN = Pattern.compile("^\\w+$");

    private static final String[] EMPTY_NAMES = new String[0];

    private static final Splitter PATH_SPLITTER = Splitter.on('/');

    private final String prefix;

    /**
     * The original path pattern specified in the constructor.
     */
    private final String pathPattern;

    private final String normalizedPathPattern;

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
     * <p>e.g. "/{x}/{y}/{z}" -> "/\0/\0/\0"</p>
     * <p>Set a skeletal form with the patterns described in {@link Route#paths()}.</p>
     */
    private final String skeleton;

    private final List<String> paths;

    /**
     * The names of the path parameters in the order of appearance.
     */
    private final String[] paramNameArray;

    /**
     * The names of the path parameters this mapping will extract.
     */
    private final Set<String> paramNames;

    /**
     * Create a {@link ParameterizedPathMapping} instance from given {@code pathPattern}.
     *
     * @param pathPattern the {@link String} that contains path params.
     *                    e.g. {@code /users/{name}}, {@code /users/:name}, {@code /users/{*name}} or
     *                    {@code /users/:*name}
     *
     * @throws IllegalArgumentException if the {@code pathPattern} is invalid.
     */
    ParameterizedPathMapping(String pathPattern) {
        this("", pathPattern);
    }

    private ParameterizedPathMapping(String prefix, String pathPattern) {
        if (!Flags.allowSemicolonInPathComponent()) {
            checkArgument(prefix.indexOf(';') < 0, "prefix: %s (expected not to have a ';')", prefix);
            checkArgument(pathPattern.indexOf(';') < 0,
                          "pathPattern: %s (expected not to have a ';')", pathPattern);
        }
        this.prefix = prefix;
        requireNonNull(pathPattern, "pathPattern");

        if (!pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (must start with '/')");
        }

        if (!VALID_PATTERN.matcher(pathPattern).matches()) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (invalid pattern)");
        }

        if (!isValidCaptureRestPattern(pathPattern)) {
            throw new IllegalArgumentException(
                    "pathPattern: " + pathPattern + " (invalid capture rest pattern)");
        }

        final StringJoiner patternJoiner = new StringJoiner("/");
        final StringJoiner normalizedPatternJoiner = new StringJoiner("/");
        final StringJoiner skeletonJoiner = new StringJoiner("/");

        final List<String> paramNames = new ArrayList<>();
        for (String token : PATH_SPLITTER.split(pathPattern)) {
            final String paramName = paramName(token);
            if (paramName == null) {
                // If the token escapes the first colon, then clean it. We don't need to handle '{'
                // since it's not an allowed path character per rfc3986.
                if (token.startsWith("\\:")) {
                    token = token.substring(1);
                }

                patternJoiner.add(token);
                normalizedPatternJoiner.add(token);
                skeletonJoiner.add(token);
                continue;
            }
            final boolean captureRestPathMatching = isCaptureRestPathMatching(token);
            final int paramNameIdx = paramNames.indexOf(paramName);
            if (paramNameIdx < 0) {
                // If the given token appeared first time, add it to the set and
                // replace it with a capturing group expression in regex.
                paramNames.add(paramName);
                if (captureRestPathMatching) {
                    patternJoiner.add("(.*)");
                } else {
                    patternJoiner.add("([^/]+)");
                }
            } else {
                // If the given token appeared before, replace it with a back-reference expression
                // in regex.
                patternJoiner.add("\\" + (paramNameIdx + 1));
            }

            normalizedPatternJoiner.add((captureRestPathMatching ? ":*" : ':') + paramName);
            skeletonJoiner.add(captureRestPathMatching ? "*" : "\0");
        }

        this.pathPattern = pathPattern;
        pattern = Pattern.compile(patternJoiner.toString());
        normalizedPathPattern = normalizedPatternJoiner.toString();
        skeleton = skeletonJoiner.toString();
        paths = ImmutableList.of(skeleton, skeleton);
        paramNameArray = paramNames.toArray(EMPTY_NAMES);
        this.paramNames = ImmutableSet.copyOf(paramNames);
    }

    /**
     * Returns the name of the path parameter contained in the path element. If it contains no path parameter,
     * {@code null} is returned. e.g.
     * <ul>
     *   <li>{@code "{foo}"} -> {@code "foo"}</li>
     *   <li>{@code ":bar"} -> {@code "bar"}</li>
     *   <li>{@code "baz"} -> {@code null}</li>
     *   <li>{@code "{*foo}"} -> {@code "foo"}</li>
     *   <li>{@code ":*foo"} -> {@code "foo"}</li>
     * </ul>
     */
    @Nullable
    private static String paramName(String token) {
        if (token.startsWith("{") && token.endsWith("}")) {
            final int beginIndex = token.charAt(1) == '*' ? 2 : 1;
            return token.substring(beginIndex, token.length() - 1);
        }

        if (token.startsWith(":")) {
            final int beginIndex = token.charAt(1) == '*' ? 2 : 1;
            return token.substring(beginIndex);
        }

        return null;
    }

    /**
     * Return true if path parameter contains capture the rest path pattern
     * ({@code "{*foo}"}" or {@code ":*foo"}).
     */
    private static boolean isCaptureRestPathMatching(String token) {
        return (token.startsWith("{*") && token.endsWith("}")) || token.startsWith(":*");
    }

    /**
     * Return true if the capture rest pattern specified is valid.
     */
    private static boolean isValidCaptureRestPattern(String pathPattern) {
        final Matcher matcher = CAPTURE_REST_PATTERN.matcher(pathPattern);
        if (!matcher.find()) {
            // Return true if the path does not include the capture rest pattern.
            return true;
        }
        final String paramName = MoreObjects.firstNonNull(matcher.group(1), matcher.group(2));
        // The variable name must be at least a character of alphabet, number and underscore.
        if (!CAPTURE_REST_VARIABLE_NAME_PATTERN.matcher(paramName).matches()) {
            return false;
        }
        // The capture rest pattern must be located at the end of the path.
        return pathPattern.length() == matcher.end();
    }

    /**
     * Returns the skeleton.
     */
    String skeleton() {
        return skeleton;
    }

    @Override
    PathMapping doWithPrefix(String prefix) {
        return new ParameterizedPathMapping(prefix, concatPaths(prefix, pathPattern));
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String patternString() {
        return normalizedPathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.PARAMETERIZED;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final Matcher matcher = pattern.matcher(routingCtx.path());
        if (!matcher.matches()) {
            return null;
        }

        final RoutingResultBuilder builder = RoutingResult.builderWithExpectedNumParams(paramNameArray.length)
                                                          .path(mappedPath(prefix, routingCtx.path()))
                                                          .query(routingCtx.query());

        for (int i = 0; i < paramNameArray.length; i++) {
            builder.rawParam(paramNameArray[i], matcher.group(i + 1));
        }
        return builder;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParameterizedPathMapping)) {
            return false;
        }

        final ParameterizedPathMapping that = (ParameterizedPathMapping) o;

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
