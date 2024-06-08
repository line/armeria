/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An abstract builder class for building a {@link CircuitBreakerMapping}
 * based on a combination of host, method and path.
 */
@UnstableApi
public abstract class AbstractCircuitBreakerMappingBuilder
        <SELF extends AbstractCircuitBreakerMappingBuilder<SELF>> {

    private boolean perHost;
    private boolean perMethod;
    private boolean perPath;

    /**
     * Creates an empty builder where all mapping keys are disabled by default.
     */
    protected AbstractCircuitBreakerMappingBuilder() {}

    /**
     * Return {@code this}.
     */
    @SuppressWarnings("unchecked")
    protected final SELF self() {
        return (SELF) this;
    }

    /**
     * Adds host dimension to the mapping Key.
     */
    public SELF perHost() {
        perHost = true;
        return self();
    }

    /**
     * Adds method dimension to the mapping Key.
     */
    public SELF perMethod() {
        perMethod = true;
        return self();
    }

    /**
     * Adds path dimension to the mapping Key.
     */
    public SELF perPath() {
        perPath = true;
        return self();
    }

    /**
     * Returns whether the host dimension is enabled for the mapping.
     */
    protected final boolean isPerHost() {
        return perHost;
    }

    /**
     * Returns whether the method dimension is enabled for the mapping.
     */
    protected final boolean isPerMethod() {
        return perMethod;
    }

    /**
     * Returns whether the path dimension is enabled for the mapping.
     */
    protected final boolean isPerPath() {
        return perPath;
    }

    /**
     * Returns whether the set dimensions are valid.
     * At least one mapping key must be set.
     */
    protected final boolean validateMappingKeys() {
        return perHost || perMethod || perPath;
    }
}
