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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for a {@link DynamicEndpointGroup} and its subtypes.
 */
@UnstableApi
public abstract class AbstractDynamicEndpointGroupBuilder {

    private boolean allowEmptyEndpoints;

    /**
     * Creates a new instance.
     */
    protected AbstractDynamicEndpointGroupBuilder() {}

    /**
     * Sets whether to allow an empty {@link Endpoint} list.
     * If unspecified, an empty {@link Endpoint} list is not allowed.
     */
    protected AbstractDynamicEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        return this;
    }

    /**
     * Returns whether an empty {@link Endpoint} list should be allowed.
     */
    protected final boolean shouldAllowEmptyEndpoints() {
        return allowEmptyEndpoints;
    }
}
