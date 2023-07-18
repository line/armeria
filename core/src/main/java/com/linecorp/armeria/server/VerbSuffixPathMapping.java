/*
 * Copyright 2022 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;

final class VerbSuffixPathMapping extends AbstractPathMapping {
    static final Pattern VERB_PATTERN = Pattern.compile("[^/]+:([a-zA-Z0-9-_]+)$");

    private final PathMapping basePathMapping;
    private final String verb;
    private final int verbLength;
    private final String patternString;
    private final List<String> paths;

    VerbSuffixPathMapping(PathMapping basePathMapping, String verb) {
        this.basePathMapping = requireNonNull(basePathMapping, "basePathMapping");
        this.verb = ':' + requireNonNull(verb, "verb");
        verbLength = this.verb.length();
        patternString = basePathMapping.patternString() + this.verb;
        // Add escape character '\' to mark ':' as a plain character, not a parameter marker.
        paths = basePathMapping.paths().stream().map(p -> p + '\\' + this.verb).collect(toImmutableList());
    }

    @Override
    @Nullable RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final String path = routingCtx.path();

        if (!path.endsWith(verb)) {
            return null;
        }

        final int basePathLength = path.length() - verbLength;
        if (basePathLength <= 0 || path.charAt(basePathLength - 1) == '/') {
            return null;
        }

        final String basePath = path.substring(0, path.length() - verbLength);
        return basePathMapping.apply(routingCtx.overridePath(basePath));
    }

    @Override
    public Set<String> paramNames() {
        return basePathMapping.paramNames();
    }

    @Override
    public String patternString() {
        return patternString;
    }

    @Override
    public RoutePathType pathType() {
        return basePathMapping.pathType();
    }

    @Override
    public List<String> paths() {
        return paths;
    }
}
