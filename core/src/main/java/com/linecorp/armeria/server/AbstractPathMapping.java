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

import static com.linecorp.armeria.internal.PathMappingUtil.UNKNOWN_LOGGER_NAME;
import static com.linecorp.armeria.internal.PathMappingUtil.ensureAbsolutePath;

import java.util.Optional;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(PathMappingContext)}.
 */
public abstract class AbstractPathMapping implements PathMapping {

    /**
     * {@inheritDoc} This method performs sanity checks on the specified {@code path} and calls
     * {@link #doApply(PathMappingContext)}.
     */
    @Override
    public final PathMappingResult apply(PathMappingContext mappingCtx) {
        ensureAbsolutePath(mappingCtx.path(), "path");
        return doApply(mappingCtx);
    }

    /**
     * Invoked by {@link #apply(PathMappingContext)} to perform the actual path matching and path parameter
     * extraction.
     *
     * @param mappingCtx a context to find the {@link Service}
     *
     * @return a non-empty {@link PathMappingResult} if the specified {@code path} matches this mapping.
     *         {@link PathMappingResult#empty()} if not matches.
     */
    protected abstract PathMappingResult doApply(PathMappingContext mappingCtx);

    @Override
    public String loggerName() {
        return UNKNOWN_LOGGER_NAME;
    }

    @Override
    public String meterTag() {
        return "__UNKNOWN_PATH__";
    }

    @Override
    public Optional<String> exactPath() {
        return Optional.empty();
    }

    @Override
    public Optional<String> prefix() {
        return Optional.empty();
    }
}
