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

final class CatchAllPathMapping extends AbstractPathMapping {

    static final CatchAllPathMapping INSTANCE = new CatchAllPathMapping();

    private static final Optional<String> PREFIX_PATH_OPT = Optional.of("/");
    private static final String LOGGER_NAME = loggerName("/"); // "__ROOT__"

    private CatchAllPathMapping() {}

    @Override
    protected PathMappingResult doApply(PathMappingContext mappingCtx) {
        return PathMappingResult.of(mappingCtx.path(), mappingCtx.query());
    }

    @Override
    public Set<String> paramNames() {
        return ImmutableSet.of();
    }

    @Override
    public String loggerName() {
        return LOGGER_NAME;
    }

    @Override
    public String meterTag() {
        return "catch-all";
    }

    @Override
    public Optional<String> prefix() {
        return PREFIX_PATH_OPT;
    }

    @Override
    public String toString() {
        return "catchAll";
    }
}
