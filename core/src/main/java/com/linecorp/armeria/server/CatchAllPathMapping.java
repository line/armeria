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

import static com.linecorp.armeria.server.RouteBuilder.prefixPathMapping;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

final class CatchAllPathMapping extends AbstractPathMapping {

    static final CatchAllPathMapping INSTANCE = new CatchAllPathMapping();

    private static final List<String> paths = ImmutableList.of("/", "/*");

    private CatchAllPathMapping() {}

    @Override
    public PathMapping doWithPrefix(String prefix) {
        return prefixPathMapping(prefix, true);
    }

    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        return RoutingResult.builder().path(routingCtx.path()).query(routingCtx.query());
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String patternString() {
        return "/*";
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
    public String toString() {
        return "catchAll";
    }
}
