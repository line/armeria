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

package com.linecorp.armeria.common.http;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

/**
 * A path param extractor. It holds three things:
 * <ul>
 *   <li>A regex-compiled form of the path. It is used for matching and extracting.</li>
 *   <li>A skeleton of the path. It is used for duplication detecting.</li>
 *   <li>A set of variables declared in the path.</li>
 * </ul>
 *
 */
public final class PathParamExtractor {

    private static final Pattern VALID_PATTERN = Pattern.compile("(/[^/{}:]+|/:[^/{}]+|/\\{[^/{}]+})+/?");

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
     * Set of the variables declared in this path param extractor.
     */
    private final ImmutableSet<String> variables;

    /**
     * Create a {@link PathParamExtractor} instance from given {@code pathPattern}.
     *
     * @param pathPattern the {@link String} that contains path params.
     *             e.g. {@code /users/{name}} or {@code /users/:name}
     *
     * @throws IllegalArgumentException if the {@code pathPattern} is invalid.
     */
    public PathParamExtractor(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");

        final String matchPath = pathPattern.charAt(0) == '/' ? pathPattern : "/" + pathPattern;
        if (!VALID_PATTERN.matcher(matchPath).matches()) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern);
        }

        StringJoiner patternJoiner = new StringJoiner("/");
        StringJoiner skeletonJoiner = new StringJoiner("/");
        Set<String> placeholders = new HashSet<>();
        for (String token : pathPattern.split("/")) {
            String variable = variable(token);
            if (variable != null) {
                if (!placeholders.contains(variable)) {
                    // If the given token is a first occurred variable mark, add it to the variable set and
                    // replace it to named group expression in regex.
                    placeholders.add(variable);
                    patternJoiner.add(String.format("(?<%s>[^/]+)", variable));
                } else {
                    // If the given token is a re-occurred variable mark, replace it to backreference expression
                    // in regex.
                    patternJoiner.add(String.format("\\k<%s>", variable));
                }
                skeletonJoiner.add("{}");
            } else {
                // If the given token is a constant, does not manipulate it.
                patternJoiner.add(token);
                skeletonJoiner.add(token);
            }
        }

        pattern = Pattern.compile(patternJoiner + "(\\?.*)*");
        skeleton = skeletonJoiner.toString();
        variables = ImmutableSet.copyOf(placeholders);
    }

    /**
     * Returns variable contained in the path element. If it contains no variable, returns null.
     *
     * <p>e.g. {variable} -> variable
     * :variable -> variable
     * constant -> constant
     */
    private String variable(String token) {
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
    public String skeleton() {
        return skeleton;
    }

    /**
     * Returns the variables.
     */
    public Set<String> variables() {
        return variables;
    }

    /**
     * Returns extracting results with given {@code path}.
     * If the {@code path} does not match, returns an empty {@link Map}.
     */
    public Map<String, String> extract(String path) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            return Collections.emptyMap();
        }

        return variables.stream()
                        .collect(toImmutableMap(Function.identity(), matcher::group));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PathParamExtractor that = (PathParamExtractor) o;

        return pattern.pattern().equals(that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return pattern.pattern().hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("pattern", pattern).toString();
    }
}
