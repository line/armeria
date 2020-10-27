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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;

/**
 * A {@link CircuitBreakerMapping} that binds a {@link CircuitBreaker} to a combination of host, method and/or
 * path. If there is no circuit breaker bound to the key, a new one is created by using the given circuit
 * breaker factory.
 */
final class KeyedCircuitBreakerMapping implements CircuitBreakerMapping {

    static final CircuitBreakerMapping hostMapping = new KeyedCircuitBreakerMapping(
            new CircuitBreakerMapping.Builder().perHost(),
            (host, method, path) -> CircuitBreaker.of(host));

    private final ConcurrentMap<String, CircuitBreaker> mapping = new ConcurrentHashMap<>();

    private final boolean isPerHost;
    private final boolean isPerMethod;
    private final boolean isPerPath;
    private final CircuitBreakerFactory factory;

    /**
     * Creates a new {@link KeyedCircuitBreakerMapping} with the given {@link CircuitBreakerMapping.Builder} and
     * {@link CircuitBreaker} factory.
     */
    KeyedCircuitBreakerMapping(CircuitBreakerMapping.Builder mappingBuilder, CircuitBreakerFactory factory) {
        requireNonNull(mappingBuilder, "mappingBuilder");
        isPerHost = mappingBuilder.isPerHost();
        isPerMethod = mappingBuilder.isPerMethod();
        isPerPath = mappingBuilder.isPerPath();
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public CircuitBreaker get(ClientRequestContext ctx, Request req) throws Exception {
        if (!isPerHost && !isPerMethod && !isPerPath) {
            // should never reach here.
            throw new Error();
        }
        final String host = isPerHost ? host(ctx) : null;
        final String method = isPerMethod ? method(ctx) : null;
        final String path = isPerPath ? path(ctx) : null;
        final String key =
                (isPerHost ? host : "") + '#' + (isPerMethod ? method : "") + '#' + (isPerPath ? path : "");
        final CircuitBreaker circuitBreaker = mapping.get(key);
        if (circuitBreaker != null) {
            return circuitBreaker;
        }
        return mapping.computeIfAbsent(key, mapKey -> factory.apply(host, method, path));
    }

    private static String host(ClientRequestContext ctx) {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            return "UNKNOWN";
        } else {
            final String ipAddr = endpoint.ipAddr();
            if (ipAddr == null || endpoint.isIpAddrOnly()) {
                return endpoint.authority();
            } else {
                return endpoint.authority() + '/' + ipAddr;
            }
        }
    }

    private static String method(ClientRequestContext ctx) {
        final RpcRequest rpcReq = ctx.rpcRequest();
        return rpcReq != null ? rpcReq.method() : ctx.method().name();
    }

    private static String path(ClientRequestContext ctx) {
        final HttpRequest request = ctx.request();
        return request == null ? "" : request.path();
    }
}
