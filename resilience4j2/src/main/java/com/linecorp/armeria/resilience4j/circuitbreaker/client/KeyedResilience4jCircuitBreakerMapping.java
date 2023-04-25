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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerMappingUtil.host;
import static com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerMappingUtil.method;
import static com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerMappingUtil.path;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.resilience4j.circuitbreaker.Resilience4jCircuitBreakerFactory;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

final class KeyedResilience4jCircuitBreakerMapping implements Resilience4jCircuitBreakerMapping {

    static final KeyedResilience4jCircuitBreakerMapping MAPPING =
            new KeyedResilience4jCircuitBreakerMapping(true, false, false,
                                                       CircuitBreakerRegistry.ofDefaults(),
                                                       Resilience4jCircuitBreakerFactory.of());

    private final boolean isPerHost;
    private final boolean isPerMethod;
    private final boolean isPerPath;
    private final CircuitBreakerRegistry registry;
    private final Resilience4jCircuitBreakerFactory factory;

    KeyedResilience4jCircuitBreakerMapping(
            boolean perHost, boolean perMethod, boolean perPath, CircuitBreakerRegistry registry,
            Resilience4jCircuitBreakerFactory factory) {
        isPerHost = perHost;
        isPerMethod = perMethod;
        isPerPath = perPath;
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public CircuitBreaker get(ClientRequestContext ctx, Request req) {
        final String host = isPerHost ? host(ctx) : null;
        final String method = isPerMethod ? method(ctx) : null;
        final String path = isPerPath ? path(ctx) : null;
        return factory.apply(registry, host, method, path);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("isPerHost", isPerHost)
                          .add("isPerMethod", isPerMethod)
                          .add("isPerPath", isPerPath)
                          .add("registry", registry)
                          .add("factory", factory)
                          .toString();
    }
}
