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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.logging.RequestLogProperty;

/**
 * An {@link HttpClient} decorator that handles failures of HTTP requests based on circuit breaker pattern.
 */
public class CircuitBreakerClient extends AbstractCircuitBreakerClient<HttpRequest, HttpResponse>
        implements HttpClient {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
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
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy strategy) {
        return delegate -> new CircuitBreakerClient(delegate, mapping, strategy);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
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
    public static Function<? super HttpClient, CircuitBreakerClient>
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
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy strategy) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerStrategy}.
     */
    public static CircuitBreakerClientBuilder builder(CircuitBreakerStrategy strategy) {
        return new CircuitBreakerClientBuilder(strategy);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerStrategyWithContent}.
     */
    public static CircuitBreakerClientBuilder builder(
            CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        return new CircuitBreakerClientBuilder(strategyWithContent);
    }

    private final boolean needsContentInStrategy;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping,
                         CircuitBreakerStrategy strategy) {
        super(delegate, mapping, strategy);
        needsContentInStrategy = false;
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping,
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

        ctx.log().partialFuture(RequestLogProperty.RESPONSE_HEADERS).thenAccept(log -> {
            final Throwable cause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            reportSuccessOrFailure(circuitBreaker, strategy().shouldReportAsSuccess(ctx, cause));
        });
        return response;
    }

    private static int maxSignalLength(long maxResponseLength) {
        if (maxResponseLength == 0 || maxResponseLength > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxResponseLength;
    }
}
