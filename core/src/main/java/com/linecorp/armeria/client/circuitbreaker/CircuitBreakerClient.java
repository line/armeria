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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

/**
 * An {@link HttpClient} decorator that handles failures of HTTP requests based on circuit breaker pattern.
 */
public final class CircuitBreakerClient extends AbstractCircuitBreakerClient<HttpRequest, HttpResponse>
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
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, rule);
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, ruleWithContent);
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
        requireNonNull(mapping, "mapping");
        requireNonNull(rule, "rule");
        return delegate -> new CircuitBreakerClient(delegate, mapping, rule);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(mapping, "mapping");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return delegate -> new CircuitBreakerClient(delegate, mapping, ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerMethodDecorator(Function<String, ? extends CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes an {@link HttpMethod} and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerMethodDecorator(Function<String, ? extends CircuitBreaker> factory,
                          CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostDecorator(Function<String, ? extends CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostDecorator(Function<String, ? extends CircuitBreaker> factory,
                        CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per request path with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a request path and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerPathDecorator(Function<String, ? extends CircuitBreaker> factory, CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perPath(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per request path with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a request path and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerPathDecorator(Function<String, ? extends CircuitBreaker> factory,
                        CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perPath(factory), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}.
     *
     * @deprecated Use {@link #newDecorator(CircuitBreakerMapping, CircuitBreakerRule)} with
     *             {@link CircuitBreakerMapping#perHostAndMethod(BiFunction)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(BiFunction<String, String, ? extends CircuitBreaker> factory,
                                 CircuitBreakerRule rule) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}.
     *
     * @deprecated Use {@link #newDecorator(CircuitBreakerMapping, CircuitBreakerRuleWithContent)} with
     *             {@link CircuitBreakerMapping#perHostAndMethod(BiFunction)}.
     */
    @Deprecated
    public static Function<? super HttpClient, CircuitBreakerClient>
    newPerHostAndMethodDecorator(BiFunction<String, String, ? extends CircuitBreaker> factory,
                                 CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), ruleWithContent);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified {@link CircuitBreakerRule}.
     */
    public static CircuitBreakerClientBuilder builder(CircuitBreakerRule rule) {
        return new CircuitBreakerClientBuilder(rule);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     */
    public static CircuitBreakerClientBuilder builder(
            CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return builder(ruleWithContent, CircuitBreakerClientBuilder.DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified
     * {@link CircuitBreakerRuleWithContent} and the specified {@code maxContentLength} which is required to
     * determine a {@link Response} as a success or failure.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static CircuitBreakerClientBuilder builder(
            CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent, int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new CircuitBreakerClientBuilder(ruleWithContent, maxContentLength);
    }

    private final boolean needsContentInRule;
    private final int maxContentLength;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping, CircuitBreakerRule rule) {
        super(delegate, mapping, rule);
        needsContentInRule = false;
        maxContentLength = 0;
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping,
                         CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        this(delegate, mapping, ruleWithContent, CircuitBreakerClientBuilder.DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    CircuitBreakerClient(HttpClient delegate, CircuitBreakerMapping mapping,
                         CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent, int maxContentLength) {
        super(delegate, mapping, ruleWithContent);
        needsContentInRule = true;
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req, CircuitBreaker circuitBreaker)
            throws Exception {
        final CircuitBreakerRule rule = needsContentInRule ? fromRuleWithContent() : rule();
        final HttpResponse response;
        try {
            response = unwrap().execute(ctx, req);
        } catch (Throwable cause) {
            reportSuccessOrFailure(circuitBreaker, rule.shouldReportAsSuccess(ctx, cause));
            throw cause;
        }

        final CompletableFuture<HttpResponse> responseFuture =
                ctx.log()
                   .whenAvailable(rule.requiresResponseTrailers() ? RequestLogProperty.RESPONSE_TRAILERS
                                                                  : RequestLogProperty.RESPONSE_HEADERS)
                   .thenApply(log -> {
                       final Throwable resCause =
                               log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;

                       if (needsContentInRule && resCause == null) {
                           final HttpResponseDuplicator duplicator =
                                   response.toDuplicator(ctx.eventLoop().withoutContext(),
                                                         ctx.maxResponseLength());
                           try {
                               final TruncatingHttpResponse truncatingHttpResponse =
                                       new TruncatingHttpResponse(duplicator.duplicate(), maxContentLength);

                               final CompletionStage<CircuitBreakerDecision> f =
                                       ruleWithContent().shouldReportAsSuccess(
                                               ctx, truncatingHttpResponse, null);
                               f.handle((unused1, unused2) -> {
                                   truncatingHttpResponse.abort();
                                   return null;
                               });
                               reportSuccessOrFailure(circuitBreaker, f);

                               final HttpResponse duplicate = duplicator.duplicate();
                               duplicator.close();
                               return duplicate;
                           } catch (Throwable cause) {
                               duplicator.abort(cause);
                               return Exceptions.throwUnsafely(cause);
                           }
                       } else {
                           reportSuccessOrFailure(circuitBreaker, rule.shouldReportAsSuccess(ctx, resCause));
                           return response;
                       }
                   });

        return HttpResponse.from(responseFuture);
    }
}
