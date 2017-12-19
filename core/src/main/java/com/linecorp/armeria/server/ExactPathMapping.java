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

import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

final class ExactPathMapping extends AbstractPathMapping {

    static final String PREFIX = "exact:";
    static final int PREFIX_LEN = PREFIX.length();

    private final String exactPath;
    private final String loggerName;
    private final String meterTag;
    private final Optional<String> exactPathOpt;

    ExactPathMapping(String exactPath) {
        this.exactPath = ensureAbsolutePath(exactPath, "exactPath");
        exactPathOpt = Optional.of(exactPath);
        loggerName = loggerName(exactPath);
        meterTag = PREFIX + exactPath;
    }

    @Override
    protected PathMappingResult doApply(PathMappingContext mappingCtx) {
        return exactPath.equals(mappingCtx.path()) ? PathMappingResult.of(mappingCtx.path(), mappingCtx.query())
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

    @Override
    public String meterTag() {
        return meterTag;
    }

    @Override
    public Optional<String> exactPath() {
        return exactPathOpt;
    }

    @Override
    public Optional<String> triePath() {
        return exactPathOpt;
    }

    @Override
    public int hashCode() {
        return meterTag.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ExactPathMapping &&
               (this == obj || exactPath.equals(((ExactPathMapping) obj).exactPath));
    }

    @Override
    public String toString() {
        return meterTag;
    }
}
