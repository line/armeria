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

import static com.linecorp.armeria.internal.server.RouteUtil.ensureAbsolutePath;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(RoutingContext)}.
 */
abstract class AbstractPathMapping implements PathMapping {

    /**
     * {@inheritDoc} This method performs sanity checks on the specified {@code path} and calls
     * {@link #doApply(RoutingContext)}.
     */
    @Nullable
    @Override
    public final RoutingResultBuilder apply(RoutingContext routingCtx) {
        ensureAbsolutePath(requireNonNull(routingCtx, "routingCtx").path(), "path");
        return doApply(routingCtx);
    }

    /**
     * Invoked by {@link #apply(RoutingContext)} to perform the actual path matching and path parameter
     * extraction.
     *
     * @param routingCtx a context to find the {@link HttpService}
     *
     * @return a non-empty {@link RoutingResultBuilder} if the specified {@code path} matches this mapping.
     *         {@code null} otherwise.
     */
    @Nullable
    abstract RoutingResultBuilder doApply(RoutingContext routingCtx);

    @Override
    public String toString() {
        return patternString();
    }
}
