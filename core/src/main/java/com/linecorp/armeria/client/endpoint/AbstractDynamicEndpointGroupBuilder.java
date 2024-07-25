/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for a {@link DynamicEndpointGroup} and its subtypes.
 */
@UnstableApi
public abstract class AbstractDynamicEndpointGroupBuilder
        <SELF extends AbstractDynamicEndpointGroupBuilder<SELF>> implements DynamicEndpointGroupSetters<SELF> {

    private boolean allowEmptyEndpoints = true;
    private long selectionTimeoutMillis;

    /**
     * Creates a new instance with the specified default {@code selectionTimeoutMillis}.
     */
    protected AbstractDynamicEndpointGroupBuilder(long selectionTimeoutMillis) {
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        this.selectionTimeoutMillis = selectionTimeoutMillis;
    }

    /**
     * Return {@code this}.
     */
    @SuppressWarnings("unchecked")
    protected final SELF self() {
        return (SELF) this;
    }

    // Note that we don't have `selectionStrategy()` here because some subclasses are delegating and
    // thus they use the `EndpointSelectionStrategy` of the delegate.
    // See: AbstractHealthCheckedEndpointGroupBuilder

    @Override
    public SELF allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        return self();
    }

    /**
     * Returns whether an empty {@link Endpoint} list should be allowed.
     */
    protected boolean shouldAllowEmptyEndpoints() {
        return allowEmptyEndpoints;
    }

    @Override
    public SELF selectionTimeoutMillis(long selectionTimeoutMillis) {
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        if (selectionTimeoutMillis == 0) {
            selectionTimeoutMillis = Long.MAX_VALUE;
        }
        this.selectionTimeoutMillis = selectionTimeoutMillis;
        return self();
    }

    /**
     * Returns the timeout to wait until a successful {@link Endpoint} selection.
     */
    // Intentionally leave as a non-final method so that some sub-builder classes override the visibility as
    // a workaround of multiple inheritance
    protected long selectionTimeoutMillis() {
        return selectionTimeoutMillis;
    }
}
