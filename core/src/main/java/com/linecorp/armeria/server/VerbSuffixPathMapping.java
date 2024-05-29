/*
 * Copyright 2024 LINE Corporation
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;

final class VerbSuffixPathMapping extends AbstractPathMapping {
    private static final Pattern VERB_PATH_PATTERN = Pattern.compile("[^/]+:([a-zA-Z0-9-_.~%]+)$");

    private final PathMapping basePathMapping;
    private final String verb;
    private final String colonAndVerb;
    private final int colonAndVerbLength;
    private final String patternString;
    private final List<String> paths;

    VerbSuffixPathMapping(PathMapping basePathMapping, String verb) {
        this.basePathMapping = requireNonNull(basePathMapping, "basePathMapping");
        this.verb = requireNonNull(verb, "verb");
        colonAndVerb = ':' + this.verb;
        colonAndVerbLength = colonAndVerb.length();
        patternString = basePathMapping.patternString() + colonAndVerb;
        // Add escape character '\' to mark ':' as a plain character, not a parameter marker.
        paths = basePathMapping.paths().stream().map(p -> p + '\\' + colonAndVerb).collect(toImmutableList());
    }

    @Override
    PathMapping doWithPrefix(String prefix) {
        return new VerbSuffixPathMapping(basePathMapping.withPrefix(prefix), verb);
    }

    @Override
    @Nullable
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final String path = routingCtx.path();

        if (!path.endsWith(colonAndVerb)) {
            return null;
        }

        final int basePathLength = path.length() - colonAndVerbLength;
        if (basePathLength <= 0 || path.charAt(basePathLength - 1) == '/') {
            return null;
        }

        final String basePath = path.substring(0, basePathLength);
        return basePathMapping.apply(routingCtx.withPath(basePath));
    }

    @Nullable
    static String findVerb(String pathPattern) {
        final Matcher matcher = VERB_PATH_PATTERN.matcher(pathPattern);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
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
