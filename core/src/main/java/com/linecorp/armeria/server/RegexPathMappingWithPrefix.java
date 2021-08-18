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

import static com.linecorp.armeria.internal.server.RouteUtil.PREFIX;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

final class RegexPathMappingWithPrefix extends AbstractPathMapping {

    private final String pathPrefix;
    private final PathMapping mapping;
    private final String pathPattern;
    private final List<String> regexAndPrefix;

    RegexPathMappingWithPrefix(String pathPrefix, PathMapping mapping) {
        requireNonNull(mapping, "mapping");
        // mapping should be GlobPathMapping or RegexPathMapping
        assert mapping.pathType() == RoutePathType.REGEX
                : "unexpected mapping type: " + mapping.getClass().getName();
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        this.mapping = mapping;
        regexAndPrefix = ImmutableList.of(mapping.paths().get(0), pathPrefix);

        final String patternString = mapping.patternString();
        final String normalizedPathPrefix;
        if (pathPrefix.endsWith("/") && patternString.startsWith("/")) {
            normalizedPathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
        } else {
            normalizedPathPrefix = pathPrefix;
        }
        pathPattern = normalizedPathPrefix + patternString;
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final String path = routingCtx.path();
        if (!path.startsWith(pathPrefix)) {
            return null;
        }

        final RoutingResultBuilder builder =
                mapping.apply(routingCtx.overridePath(path.substring(pathPrefix.length() - 1)));
        if (builder != null) {
            // Replace the path.
            builder.path(path);
        }

        return builder;
    }

    @Override
    public Set<String> paramNames() {
        return mapping.paramNames();
    }

    @Override
    public String patternString() {
        return pathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.REGEX_WITH_PREFIX;
    }

    @Override
    public List<String> paths() {
        return regexAndPrefix;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegexPathMappingWithPrefix)) {
            return false;
        }

        final RegexPathMappingWithPrefix that = (RegexPathMappingWithPrefix) o;
        return pathPrefix.equals(that.pathPrefix) && mapping.equals(that.mapping);
    }

    @Override
    public int hashCode() {
        return 31 * pathPrefix.hashCode() + mapping.hashCode();
    }

    @Override
    public String toString() {
        return '[' + PREFIX + pathPrefix + ", " + mapping + ']';
    }
}
