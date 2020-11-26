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

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
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
        return KeyedCircuitBreakerMapping.hostMapping;
    }

    /**
     * Returns a builder that builds a {@link CircuitBreakerMapping} by setting host, method and/or path.
     */
    static CircuitBreakerMappingBuilder builder() {
        return new CircuitBreakerMappingBuilder();
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with method name.
     *
     * @param factory the function that takes a method name and creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perMethod(Function<String, ? extends CircuitBreaker> factory) {
        requireNonNull(factory, "factory");
        return builder().perMethod().build((host, method, path) -> factory.apply(method));
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with the remote host name.
     *
     * @param factory the function that takes a host name and creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perHost(Function<String, ? extends CircuitBreaker> factory) {
        requireNonNull(factory, "factory");
        return builder().perHost().build((host, method, path) -> factory.apply(host));
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with the request path.
     *
     * @param factory the function that takes a path and creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perPath(Function<String, ? extends CircuitBreaker> factory) {
        requireNonNull(factory, "factory");
        return builder().perPath().build((host, method, path) -> factory.apply(path));
    }

    /**
     * Creates a new {@link CircuitBreakerMapping} which maps {@link CircuitBreaker}s with the remote host and
     * method name.
     *
     * @param factory the function that takes the remote host and method name and
     *                creates a new {@link CircuitBreaker}
     */
    static CircuitBreakerMapping perHostAndMethod(
            BiFunction<String, String, ? extends CircuitBreaker> factory) {
        requireNonNull(factory, "factory");
        return builder().perHost().perMethod().build((host, method, path) -> factory.apply(host, method));
    }

    /**
     * Returns the {@link CircuitBreaker} mapped to the given parameters.
     */
    CircuitBreaker get(ClientRequestContext ctx, Request req) throws Exception;
}
