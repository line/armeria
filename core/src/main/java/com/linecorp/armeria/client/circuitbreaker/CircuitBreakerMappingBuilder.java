/*
 * Copyright 2020 LINE Corporation
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

/**
 * Builder class for building a {@link CircuitBreakerMapping} based on a combination of host, method and path.
 */
public final class CircuitBreakerMappingBuilder {
    private boolean perHost;
    private boolean perMethod;
    private boolean perPath;

    /**
     * Adds host dimension to the mapping Key.
     * @return this builder.
     */
    public CircuitBreakerMappingBuilder perHost() {
        perHost = true;
        return this;
    }

    /**
     * Adds method dimension to the mapping Key.
     * @return this builder.
     */
    public CircuitBreakerMappingBuilder perMethod() {
        perMethod = true;
        return this;
    }

    /**
     * Adds path dimension to the mapping Key.
     * @return this builder.
     */
    public CircuitBreakerMappingBuilder perPath() {
        perPath = true;
        return this;
    }

    /**
     * Builds the {@link CircuitBreakerMapping} using a three-dimensional factory.
     * @return a {@link CircuitBreakerMapping} based on the added dimensions.
     */
    public CircuitBreakerMapping build(CircuitBreakerFactory factory) {
        if (!perHost && !perMethod && !perPath) {
            throw new IllegalStateException("A CircuitBreakerMapping must be per host, method and/or path");
        }
        return new KeyedCircuitBreakerMapping(this, factory);
    }

    boolean isPerHost() {
        return perHost;
    }

    boolean isPerMethod() {
        return perMethod;
    }

    boolean isPerPath() {
        return perPath;
    }

    private CircuitBreakerMappingBuilder() {}
}
