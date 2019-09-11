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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.logging.RequestLogAvailability;

import java.util.function.Function;

/**
 * A {@link Client} decorator that handles failures of HTTP requests based on circuit breaker pattern.
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
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerStrategy strategy) {
        return newDecorator((ctx, req) -> circuitBreaker, strategy);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy strategy) {
        return delegate -> new CircuitBreakerHttpClient(delegate, mapping, strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a {@link HttpMethod} and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory,
                          CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory,
                        CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     */
    public static Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerHttpClientBuilder} instance with the specified {@link CircuitBreakerStrategy}.
     */
    public static CircuitBreakerHttpClientBuilder builder(CircuitBreakerStrategy strategy) {
        return new CircuitBreakerHttpClientBuilder(strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerHttpClientBuilder} with the specified {@link CircuitBreakerStrategy} and {@link CircuitBreakerStrategyWithContent}.
     */
    public static CircuitBreakerHttpClientBuilder builder(CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        return new CircuitBreakerHttpClientBuilder(strategyWithContent);
    }

    private final boolean needsContentInStrategy;



    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    CircuitBreakerHttpClient(Client<HttpRequest, HttpResponse> delegate, CircuitBreakerMapping mapping,
                             CircuitBreakerStrategy strategy) {
        super(delegate, mapping, strategy);
        needsContentInStrategy = false;
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    CircuitBreakerHttpClient(Client<HttpRequest, HttpResponse> delegate, CircuitBreakerMapping mapping,
                             CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        super(delegate, mapping, strategyWithContent);
        needsContentInStrategy = true;
    }



    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req, CircuitBreaker circuitBreaker)
            throws Exception {
        final HttpResponse response;
        try {
            response = delegate().execute(ctx, req);
        } catch (Throwable cause) {
            if (needsContentInStrategy) {
                reportSuccessOrFailure(circuitBreaker, strategyWithContent().shouldReportAsSuccess(
                        ctx, HttpResponse.ofFailure(cause)));
            } else {
                reportSuccessOrFailure(circuitBreaker, strategy().shouldReportAsSuccess(ctx, cause));
            }
            throw cause;
        }

        if (needsContentInStrategy) {
            final HttpResponseDuplicator resDuplicator = new HttpResponseDuplicator(
                    response, maxSignalLength(ctx.maxResponseLength()), ctx.eventLoop());
            reportSuccessOrFailure(circuitBreaker, strategyWithContent().shouldReportAsSuccess(
                    ctx, resDuplicator.duplicateStream()));
            return resDuplicator.duplicateStream(true);
        }

        ctx.log().addListener(log -> {
            final Throwable cause =
                    log.isAvailable(RequestLogAvailability.RESPONSE_END) ? log.responseCause() : null;
            reportSuccessOrFailure(circuitBreaker, strategy().shouldReportAsSuccess(ctx, cause));
        }, RequestLogAvailability.RESPONSE_HEADERS);
        return response;
    }

    private static int maxSignalLength(long maxResponseLength) {
        if (maxResponseLength == 0 || maxResponseLength > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxResponseLength;
    }
}
