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

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.fromCircuitBreakerStrategy;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.fromCircuitBreakerStrategyWithContent;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
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
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRule rule) {
        return newDecorator((ctx, req) -> circuitBreaker, rule);
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @deprecated Use {@link #newDecorator(CircuitBreaker, CircuitBreakerRule)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return newDecorator(circuitBreaker, fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerRule rule) {
        return delegate -> new CircuitBreakerClient(delegate, mapping, rule);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @deprecated Use {@link #newDecorator(CircuitBreakerMapping, CircuitBreakerRule)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return newDecorator(mapping, fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}
     *
     * @deprecated Use {@link #newPerMethodDecorator(Function, CircuitBreakerRule)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerMethodDecorator(Function<String, CircuitBreaker> factory, CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return newPerMethodDecorator(factory, fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     *
     * @deprecated Use {@link #newPerHostDecorator(Function, CircuitBreakerRule)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostDecorator(Function<String, CircuitBreaker> factory,
                        CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return newPerHostDecorator(factory, fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerStrategy}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     *
     * @deprecated Use {@link #newPerHostAndMethodDecorator(Function, CircuitBreakerRule)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(Function<String, CircuitBreaker> factory,
                                 CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return newPerHostAndMethodDecorator(factory, fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified {@link CircuitBreakerRule}.
     */
    public static CircuitBreakerClientBuilder builder(CircuitBreakerRule rule) {
        return new CircuitBreakerClientBuilder(rule);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified {@link CircuitBreakerStrategy}.
     *
     * @deprecated Use {@link #builder(CircuitBreakerRule)}.
     */
    @Deprecated
    public static CircuitBreakerClientBuilder builder(CircuitBreakerStrategy strategy) {
        requireNonNull(strategy, "strategy");
        return builder(fromCircuitBreakerStrategy(strategy));
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     */
    public static CircuitBreakerClientBuilder builder(
            CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return new CircuitBreakerClientBuilder(ruleWithContent);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerStrategyWithContent}.
     *
     * @deprecated Use {@link #builder(CircuitBreakerRuleWithContent)}.
     */
    @Deprecated
    public static CircuitBreakerClientBuilder builder(
            CircuitBreakerStrategyWithContent<HttpResponse> strategyWithContent) {
        requireNonNull(strategyWithContent, "strategyWithContent");
        return builder(fromCircuitBreakerStrategyWithContent(strategyWithContent));
    }

    private final boolean needsContentInRule;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping, CircuitBreakerRule rule) {
        super(delegate, mapping, rule);
        needsContentInRule = false;
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping,
                         CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        super(delegate, mapping, ruleWithContent);
        needsContentInRule = true;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req, CircuitBreaker circuitBreaker)
            throws Exception {
        final HttpResponse response;
        try {
            response = delegate().execute(ctx, req);
        } catch (Throwable cause) {
            if (needsContentInRule) {
                reportSuccessOrFailure(circuitBreaker, ruleWithContent().shouldReportAsSuccess(
                        ctx, HttpResponse.ofFailure(cause), cause));
            } else {
                reportSuccessOrFailure(circuitBreaker, rule().shouldReportAsSuccess(ctx, cause));
            }
            throw cause;
        }

        final CompletableFuture<HttpResponse> responseFuture =
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenApply(log -> {
                    if (needsContentInRule) {
                        try (HttpResponseDuplicator duplicator =
                                     response.toDuplicator(ctx.eventLoop(), ctx.maxResponseLength())) {
                            reportSuccessOrFailure(circuitBreaker, ruleWithContent().shouldReportAsSuccess(
                                    ctx, duplicator.duplicate(), null));
                            return duplicator.duplicate();
                        }
                    } else {
                        final Throwable cause =
                                log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
                        reportSuccessOrFailure(circuitBreaker, rule().shouldReportAsSuccess(ctx, cause));
                        return response;
                    }
                });
        return HttpResponse.from(responseFuture);
    }
}
