/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class CircuitBreakerClient<I extends Request, O extends Response>
        extends DecoratingClient<I, O, I, O> {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerClient.class);

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance.
     * <p>
     * Since {@link CircuitBreaker} is a unit of failure detection, Don't reuse the same instance for unrelated
     * services.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to be used
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, CircuitBreakerClient<I, O>>
    newDecorator(CircuitBreaker circuitBreaker) {
        return newDecorator((ctx, req) -> circuitBreaker);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per method.
     *
     * @param factory A function that takes a method name and creates a new {@link CircuitBreaker}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, CircuitBreakerClient<I, O>>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host.
     *
     * @param factory A function that takes a host name and creates a new {@link CircuitBreaker}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, CircuitBreakerClient<I, O>>
    newPerHostDecorator(Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST, factory));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and method.
     *
     * @param factory A function that takes a host+method name and creates a new {@link CircuitBreaker}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, CircuitBreakerClient<I, O>>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST_AND_METHOD, factory));
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<? super I, ? extends O>, CircuitBreakerClient<I, O>>
    newDecorator(CircuitBreakerMapping mapping) {
        return delegate -> new CircuitBreakerClient<>(delegate, mapping);
    }

    private final CircuitBreakerMapping mapping;

    CircuitBreakerClient(Client<? super I, ? extends O> delegate, CircuitBreakerMapping mapping) {
        super(delegate);
        this.mapping = requireNonNull(mapping, "mapping");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {

        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return delegate().execute(ctx, req);
        }

        if (circuitBreaker.canRequest()) {
            final O response;
            try {
                response = delegate().execute(ctx, req);
            } catch (Throwable cause) {
                circuitBreaker.onFailure(cause);
                throw cause;
            }

            response.closeFuture().handle(voidFunction((res, cause) -> {
                // Report whether the invocation has succeeded or failed.
                if (cause == null) {
                    circuitBreaker.onSuccess();
                } else {
                    circuitBreaker.onFailure(cause);
                }
            })).exceptionally(CompletionActions::log);

            return response;
        } else {
            // the circuit is tripped; raise an exception without delegating.
            throw new FailFastException(circuitBreaker);
        }
    }
}
