/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

public final class SetPathMapping extends AbstractPathMapping {

    static final String PREFIX = "path set:";

    private final Set<String> pathSet;
    private final String pathSetStr;
    private final String loggerName;
    private final String strVal;

    /**
     * Constructs {@link SetPathMapping} with the path set it maps.
     *
     * @param pathSet the set of path.
     */
    public SetPathMapping(Set<String> pathSet) {
        this.pathSet = requireNonNull(pathSet, "pathSet");
        pathSetStr = String.join(", ", pathSet);
        loggerName = loggerName(pathSetStr);
        strVal = PREFIX + pathSetStr;
    }

    @Override
    protected PathMappingResult doApply(PathMappingContext ctx) {
        return pathSet.contains(ctx.path()) ? PathMappingResult.of(ctx.path(), ctx.query())
                                            : PathMappingResult.empty();
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    public String metricName() {
        return pathSetStr;
    }

    @Override
    public int hashCode() {
        return strVal.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SetPathMapping &&
            (this == obj || pathSet.equals(((SetPathMapping) obj).pathSet));
    }

    @Override
    public String toString() {
        return strVal;
    }
}
