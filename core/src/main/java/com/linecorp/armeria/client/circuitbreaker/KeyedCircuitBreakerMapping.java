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
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;

/**
 * A {@link CircuitBreakerMapping} that binds a {@link CircuitBreaker} to its key. {@link KeySelector} is used
 * to resolve the key from a {@link Request}. If there is no circuit breaker bound to the key, a new one is
 * created by using the given circuit breaker factory.
 */
final class KeyedCircuitBreakerMapping implements CircuitBreakerMapping {

    static final CircuitBreakerMapping hostMapping =
            new KeyedCircuitBreakerMapping(MappingKey.HOST, (host, method) -> CircuitBreaker.of(host));

    private final ConcurrentMap<String, CircuitBreaker> mapping = new ConcurrentHashMap<>();

    private final MappingKey mappingKey;
    private final BiFunction<String, String, ? extends CircuitBreaker> factory;

    /**
     * Creates a new {@link KeyedCircuitBreakerMapping} with the given {@link KeySelector} and
     * {@link CircuitBreaker} factory.
     */
    KeyedCircuitBreakerMapping(MappingKey mappingKey,
                               BiFunction<String, String, ? extends CircuitBreaker> factory) {
        this.mappingKey = requireNonNull(mappingKey, "mappingKey");
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public CircuitBreaker get(ClientRequestContext ctx, Request req) throws Exception {
        final String key;
        final String host;
        final String method;
        switch (mappingKey) {
            case HOST:
                key = host = KeySelector.HOST.get(ctx, req);
                method = null;
                break;
            case METHOD:
                host = null;
                key = method = KeySelector.METHOD.get(ctx, req);
                break;
            case HOST_AND_METHOD:
                host = KeySelector.HOST.get(ctx, req);
                method = KeySelector.METHOD.get(ctx, req);
                key = host + '#' + method;
                break;
            default:
                // should never reach here.
                throw new Error();
        }
        final CircuitBreaker circuitBreaker = mapping.get(key);
        if (circuitBreaker != null) {
            return circuitBreaker;
        }
        return mapping.computeIfAbsent(key, mapKey -> factory.apply(host, method));
    }

    /**
     * Returns the mapping key of the given {@link Request}.
     *
     * @deprecated Use static methods in {@link CircuitBreakerMapping}.
     */
    @Deprecated
    @FunctionalInterface
    public interface KeySelector<K> {

        /**
         * A {@link KeySelector} that returns remote method name as a key.
         *
         * @deprecated Use {@link CircuitBreakerMapping#perMethod(Function)}.
         */
        @Deprecated
        KeySelector<String> METHOD = (ctx, req) -> {
            final RpcRequest rpcReq = ctx.rpcRequest();
            return rpcReq != null ? rpcReq.method() : ctx.method().name();
        };

        /**
         * A {@link KeySelector} that returns a key consisted of remote host name, IP address and port number.
         *
         * @deprecated Use {@link CircuitBreakerMapping#perHost(Function)}.
         */
        @Deprecated
        KeySelector<String> HOST =
                (ctx, req) -> {
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
                };

        /**
         * A {@link KeySelector} that returns a key consisted of remote host name, IP address, port number
         * and method name.
         *
         * @deprecated Use {@link CircuitBreakerMapping#perHostAndMethod(BiFunction)}.
         */
        @Deprecated
        KeySelector<String> HOST_AND_METHOD =
                (ctx, req) -> HOST.get(ctx, req) + '#' + METHOD.get(ctx, req);

        /**
         * Returns the mapping key of the given {@link Request}.
         */
        K get(ClientRequestContext ctx, Request req) throws Exception;
    }

    enum MappingKey {
        HOST,
        METHOD,
        HOST_AND_METHOD;
    }
}
