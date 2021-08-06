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

import static com.linecorp.armeria.internal.server.RouteUtil.REGEX;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;

final class RegexPathMapping extends AbstractPathMapping {

    private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([^>]+)>");

    private final Pattern regex;
    private final Set<String> paramNames;
    private final String pathPattern;
    private final String strVal;
    private final List<String> paths;

    RegexPathMapping(Pattern regex) {
        this.regex = requireNonNull(regex, "regex");
        paramNames = findParamNames(regex);
        pathPattern = regex.pattern();
        strVal = REGEX + pathPattern;
        paths = ImmutableList.of(regex.pattern());
    }

    private static Set<String> findParamNames(Pattern regex) {
        final Matcher matcher = NAMED_GROUP_PATTERN.matcher(regex.pattern());
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        int pos = 0;
        while (matcher.find(pos)) {
            builder.add(matcher.group(1));
            pos = matcher.end();
        }
        return builder.build();
    }

    @Nullable
    @Override
    protected RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final Matcher matcher = regex.matcher(routingCtx.path());
        if (!matcher.find()) {
            return null;
        }

        final RoutingResultBuilder builder = RoutingResult.builderWithExpectedNumParams(paramNames.size())
                                                          .path(routingCtx.path())
                                                          .query(routingCtx.query());
        for (String name : paramNames) {
            final String value = matcher.group(name);
            if (value == null) {
                continue;
            }
            builder.rawParam(name, value);
        }

        return builder;
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String patternString() {
        return pathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.REGEX;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Override
    public int hashCode() {
        return regex.pattern().hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof RegexPathMapping &&
               (this == obj || regex.pattern().equals(((RegexPathMapping) obj).regex.pattern()));
    }

    @Override
    public String toString() {
        return strVal;
    }
}
