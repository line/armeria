/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 */
public final class CircuitBreakerClient extends DecoratingClient {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance.
     * <p>
     * Since {@link CircuitBreaker} is a unit of failure detection, Don't reuse the same instance for unrelated
     * services.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to be used
     */
    public static Function<Client, Client> newDecorator(CircuitBreaker circuitBreaker) {
        return newDecorator((eventLoop, uri, options, codec, method, args) -> circuitBreaker);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per method.
     *
     * @param factory A function that takes a method name and creates a new {@link CircuitBreaker}.
     */
    public static Function<Client, Client> newPerMethodDecorator(Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host.
     *
     * @param factory A function that takes a host name and creates a new {@link CircuitBreaker}.
     */
    public static Function<Client, Client> newPerHostDecorator(Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST, factory));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and method.
     *
     * @param factory A function that takes a host+method name and creates a new {@link CircuitBreaker}.
     */
    public static Function<Client, Client> newPerHostAndMethodDecorator(
            Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST_AND_METHOD, factory));
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping}.
     */
    public static Function<Client, Client> newDecorator(CircuitBreakerMapping mapping) {
        return client -> new CircuitBreakerClient(client, mapping);
    }

    CircuitBreakerClient(Client client, CircuitBreakerMapping mapping) {
        super(client, Function.identity(), invoker -> new CircuitBreakerRemoteInvoker(invoker, mapping));
    }

}
