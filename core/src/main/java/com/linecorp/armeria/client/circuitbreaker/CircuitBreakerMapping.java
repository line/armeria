/*
 * Copyright 2016 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;
import com.linecorp.armeria.common.Request;

/**
 * Returns a {@link CircuitBreaker} instance from remote invocation parameters.
 */
@FunctionalInterface
public interface CircuitBreakerMapping {

    /**
     * Returns the default {@link CircuitBreakerMapping}.
     */
    static CircuitBreakerMapping ofDefault() {
        return KeyedCircuitBreakerMapping.defaultMapping;
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with method name.
     *
     * @param factory A function that takes a method name and creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perMethod(Function<String, CircuitBreaker> factory) {
        return new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, requireNonNull(factory, "factory"));
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with the remote host name.
     *
     * @param factory A function that takes a host name and creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perHost(Function<String, CircuitBreaker> factory) {
        return new KeyedCircuitBreakerMapping<>(KeySelector.HOST, requireNonNull(factory, "factory"));
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with the remote host and
     * method name.
     *
     * @param factory A function that takes the remote host and method name and
     *                creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perHostAndMethod(Function<String, CircuitBreaker> factory) {
        return new KeyedCircuitBreakerMapping<>(KeySelector.HOST_AND_METHOD,
                                                requireNonNull(factory, "factory"));
    }

    /**
     * Returns the {@link CircuitBreaker} mapped to the given parameters.
     */
    CircuitBreaker get(ClientRequestContext ctx, Request req) throws Exception;
}
