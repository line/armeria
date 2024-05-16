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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.server.RouteUtil.ensureAbsolutePath;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

final class ExactPathMapping extends AbstractPathMapping {

    private static final Pattern ESCAPE_COLON = Pattern.compile("/\\\\:");
    private final String prefix;
    private final String exactPath;
    private final List<String> paths;

    ExactPathMapping(String exactPath) {
        this("", exactPath);
    }

    private ExactPathMapping(String prefix, String exactPath) {
        if (!Flags.allowSemicolonInPathComponent()) {
            checkArgument(prefix.indexOf(';') < 0, "prefix: %s (expected not to have a ';')", prefix);
            checkArgument(exactPath.indexOf(';') < 0, "exactPath: %s (expected not to have a ';')", exactPath);
        }
        this.prefix = prefix;
        ensureAbsolutePath(exactPath, "exactPath");
        exactPath = ESCAPE_COLON.matcher(exactPath).replaceAll("/:");
        this.exactPath = exactPath;
        paths = ImmutableList.of(exactPath, exactPath);
    }

    @Override
    PathMapping doWithPrefix(String prefix) {
        return new ExactPathMapping(prefix, concatPaths(prefix, exactPath));
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        return exactPath.equals(routingCtx.path()) ? RoutingResult.builder()
                                                                  .path(mappedPath(prefix, routingCtx.path()))
                                                                  .query(routingCtx.query())
                                                   : null;
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String patternString() {
        return exactPath;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.EXACT;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Override
    public int hashCode() {
        return exactPath.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ExactPathMapping &&
               (this == obj || exactPath.equals(((ExactPathMapping) obj).exactPath));
    }
}
