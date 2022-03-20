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

/**
 * A skeletal builder implementation for an {@link EndpointGroup} builder.
 */
public abstract class AbstractDynamicEndpointGroupBuilder {
    private boolean allowEmptyEndpoints;

    protected AbstractDynamicEndpointGroupBuilder() { }

    /**
     * Creates a new {@link AbstractDynamicEndpointGroupBuilder}.
     *
     * @param allowEmptyEndpoints whether the empty endpoints are allowed.
     */
    protected AbstractDynamicEndpointGroupBuilder(boolean allowEmptyEndpoints) {
        this.allowEmptyEndpoints = allowEmptyEndpoints;
    }

    /**
     * Sets to allow empty endpoints
     * @see AbstractDynamicEndpointGroupBuilder#allowEmptyEndpoints(boolean)
     */
    protected AbstractDynamicEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        return this;
    }

    /**
     * Returns whether the empty endpoints are allowed.
     */
    protected final boolean isAllowEmptyEndpoints() {
        return allowEmptyEndpoints;
    }


}
