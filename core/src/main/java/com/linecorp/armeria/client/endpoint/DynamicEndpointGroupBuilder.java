/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a new {@link DynamicEndpointGroup}.
 */
@UnstableApi
public final class DynamicEndpointGroupBuilder
        extends AbstractDynamicEndpointGroupBuilder<DynamicEndpointGroupBuilder> {

    @Nullable
    private EndpointSelectionStrategy selectionStrategy;

    DynamicEndpointGroupBuilder() {
        super(Flags.defaultConnectTimeoutMillis());
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link DynamicEndpointGroup}.
     * If unspecified, {@link EndpointSelectionStrategy#weightedRoundRobin()} is used.
     */
    public DynamicEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Returns a newly created {@link DynamicEndpointGroup} with the properties configured so far.
     */
    public DynamicEndpointGroup build() {
        if (selectionStrategy != null) {
            return new DynamicEndpointGroup(selectionStrategy, shouldAllowEmptyEndpoints(),
                                            selectionTimeoutMillis());
        }
        return new DynamicEndpointGroup(shouldAllowEmptyEndpoints());
    }
}
