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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.server.RouteUtil.PREFIX;
import static com.linecorp.armeria.internal.server.RouteUtil.ensureAbsolutePath;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;

final class PrefixPathMapping extends AbstractPathMapping {

    private final String prefix;
    private final boolean stripPrefix;
    private final List<String> paths;
    private final String pathPattern;
    private final String strVal;

    PrefixPathMapping(String prefix, boolean stripPrefix) {
        prefix = ensureAbsolutePath(prefix, "prefix");
        if (!prefix.endsWith("/")) {
            prefix += '/';
        }

        this.prefix = prefix;
        this.stripPrefix = stripPrefix;
        final String triePath = prefix + '*';
        paths = ImmutableList.of(prefix, triePath);
        pathPattern = triePath;
        strVal = PREFIX + prefix + " (stripPrefix: " + stripPrefix + ')';
    }

    @Override
    public PathMapping doWithPrefix(String prefix) {
        return new PrefixPathMapping(concatPaths(prefix, this.prefix), stripPrefix);
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final String path = routingCtx.path();
        if (!path.startsWith(prefix)) {
            return null;
        }

        return RoutingResult.builder()
                            .path(stripPrefix ? path.substring(prefix.length() - 1) : path)
                            .query(routingCtx.query());
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String patternString() {
        return pathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.PREFIX;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Override
    public int hashCode() {
        return stripPrefix ? prefix.hashCode() : -prefix.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof PrefixPathMapping)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final PrefixPathMapping that = (PrefixPathMapping) obj;
        return stripPrefix == that.stripPrefix && prefix.equals(that.prefix);
    }

    @Override
    public String toString() {
        return strVal;
    }
}
