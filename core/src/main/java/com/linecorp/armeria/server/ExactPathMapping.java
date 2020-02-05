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

import static com.linecorp.armeria.internal.server.RouteUtil.EXACT;
import static com.linecorp.armeria.internal.server.RouteUtil.ensureAbsolutePath;
import static com.linecorp.armeria.internal.server.RouteUtil.newLoggerName;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

final class ExactPathMapping extends AbstractPathMapping {

    private final String exactPath;
    private final String loggerName;
    private final String meterTag;
    private final List<String> paths;

    ExactPathMapping(String exactPath) {
        this.exactPath = ensureAbsolutePath(exactPath, "exactPath");
        paths = ImmutableList.of(exactPath, exactPath);
        loggerName = newLoggerName(exactPath);
        meterTag = EXACT + exactPath;
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        return exactPath.equals(routingCtx.path()) ? RoutingResult.builder()
                                                                  .path(routingCtx.path())
                                                                  .query(routingCtx.query())
                                                   : null;
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String meterTag() {
        return meterTag;
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
        return meterTag.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ExactPathMapping &&
               (this == obj || exactPath.equals(((ExactPathMapping) obj).exactPath));
    }

    @Override
    public String toString() {
        return meterTag;
    }
}
