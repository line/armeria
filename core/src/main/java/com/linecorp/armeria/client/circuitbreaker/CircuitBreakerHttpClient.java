/*
 * Copyright 2018 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;

/**
 * A {@link Client} decorator that handles failures of HTTP requests based on circuit breaker pattern.
 *
 */
public final class CircuitBreakerHttpClient extends CircuitBreakerClient<HttpRequest, HttpResponse> {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerStrategy<HttpResponse> strategy) {
        return newDecorator((ctx, req) -> circuitBreaker, strategy);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerStrategy}.
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy<HttpResponse> strategy) {
        return delegate -> new CircuitBreakerHttpClient(delegate, mapping, strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * @param factory a function that takes a {@link HttpMethod} and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory,
                          CircuitBreakerStrategy<HttpResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory,
                        CircuitBreakerStrategy<HttpResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST, factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy<HttpResponse> strategy) {
        return newDecorator(new KeyedCircuitBreakerMapping<>(KeySelector.HOST_AND_METHOD, factory), strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    CircuitBreakerHttpClient(Client<HttpRequest, HttpResponse> delegate, CircuitBreakerMapping mapping,
                             CircuitBreakerStrategy<HttpResponse> strategy) {
        super(delegate, mapping, strategy);
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req, CircuitBreaker circuitBreaker)
            throws Exception {
        final HttpResponse response;
        try {
            response = delegate().execute(ctx, req);
        } catch (Throwable cause) {
            reportSuccessOrFailure(circuitBreaker,
                                   strategy().shouldReportAsSuccess(HttpResponse.ofFailure(cause)));
            throw cause;
        }
        final HttpResponseDuplicator resDuplicator =
                new HttpResponseDuplicator(response, maxSignalLength(ctx.maxResponseLength()), ctx.eventLoop());
        reportSuccessOrFailure(circuitBreaker,
                               strategy().shouldReportAsSuccess(resDuplicator.duplicateStream()));
        return resDuplicator.duplicateStream(true);
    }

    private static int maxSignalLength(long maxResponseLength) {
        if (maxResponseLength == 0 || maxResponseLength > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxResponseLength;
    }
}
