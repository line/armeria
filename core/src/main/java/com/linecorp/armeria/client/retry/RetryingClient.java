/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.netty.handler.codec.DateFormatter;

/**
 * An {@link HttpClient} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingClient extends AbstractRetryingClient<HttpRequest, HttpResponse>
        implements HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RetryingClient.class);

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfig}.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static RetryingClientBuilder builder(RetryConfig<HttpResponse> retryConfig) {
        return new RetryingClientBuilder(retryConfig);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRule}.
     */
    public static RetryingClientBuilder builder(RetryRule retryRule) {
        return new RetryingClientBuilder(RetryConfig.<HttpResponse>builder0(retryRule).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryingClientBuilder(RetryConfig.builder0(retryRuleWithContent).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent} and
     * the specified {@code maxContentLength}.
     * The {@code maxContentLength} required to determine whether to retry or not. If the total length of
     * content exceeds this length and there's no retry condition matched,
     * it will hand over the stream to the client.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent,
                                                int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new RetryingClientBuilder(
                RetryConfig.builder0(retryRuleWithContent).maxContentLength(maxContentLength).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingClientBuilder builderWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return new RetryingClientBuilder(mapping);
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     */
    public static Function<? super HttpClient, RetryingClient> newDecorator(RetryRule retryRule) {
        return builder(retryRule).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return builder(retryRuleWithContent).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts) {
        return builder(retryRuleWithContent).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts)
                                 .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts,
                 long responseTimeoutMillisForEachAttempt) {
        return builder(retryRuleWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryConfig<HttpResponse> retryConfig) {
        return builder(retryConfig).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given {@link ClientRequestContext}
     *        and {@link Request}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecoratorWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return builderWithMapping(mapping).newDecorator();
    }

    private final boolean useRetryAfter;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RetryingClient(
            HttpClient delegate,
            RetryConfigMapping<HttpResponse> mapping,
            @Nullable RetryConfig<HttpResponse> retryConfig,
            boolean useRetryAfter) {
        super(delegate, mapping, retryConfig);
        this.useRetryAfter = useRetryAfter;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture, ctx.eventLoop());
        final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
        doExecute0(ctx, reqDuplicator, req, res, responseFuture);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                            HttpRequest originalReq, HttpResponse returnedRes,
                            CompletableFuture<HttpResponse> future) {
        final int totalAttempts = getTotalAttempts(ctx);
        final boolean initialAttempt = totalAttempts <= 1;
        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (originalReq.whenComplete().isCompletedExceptionally()) {
            originalReq.whenComplete().handle((unused, cause) -> {
                handleException(ctx, rootReqDuplicator, future, cause, initialAttempt);
                return null;
            });
            return;
        }
        if (returnedRes.isComplete()) {
            returnedRes.whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                handleException(ctx, rootReqDuplicator, future, abortCause, initialAttempt);
                return null;
            });
            return;
        }

        if (!setResponseTimeout(ctx)) {
            handleException(ctx, rootReqDuplicator, future, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final HttpRequest duplicateReq;
        if (initialAttempt) {
            duplicateReq = rootReqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder newHeaders = originalReq.headers().toBuilder();
            newHeaders.setInt(ARMERIA_RETRY_COUNT, totalAttempts - 1);
            duplicateReq = rootReqDuplicator.duplicate(newHeaders.build());
        }

        final ClientRequestContext derivedCtx;
        try {
            derivedCtx = newDerivedContext(ctx, duplicateReq, ctx.rpcRequest(), initialAttempt);
        } catch (Throwable t) {
            handleException(ctx, rootReqDuplicator, future, t, initialAttempt);
            return;
        }

        final HttpResponse response = executeWithFallback(unwrap(), derivedCtx,
                                                          (context, cause) -> HttpResponse.ofFailure(cause));

        final RetryConfig<HttpResponse> config = mapping().get(ctx, duplicateReq);
        if (config.requiresResponseTrailers()) {
            response.aggregate().handle((aggregated, cause) -> {
                @Nullable
                final HttpResponse response0 = cause != null ? HttpResponse.ofFailure(cause) : null;
                handleResponse(config, ctx, rootReqDuplicator, originalReq, returnedRes, future, derivedCtx,
                               response0, aggregated);
                return null;
            });
        } else {
            handleResponse(config, ctx, rootReqDuplicator, originalReq, returnedRes,
                           future, derivedCtx, response, null);
        }
    }

    private void handleResponse(RetryConfig<HttpResponse> retryConfig, ClientRequestContext ctx,
                                HttpRequestDuplicator rootReqDuplicator,
                                HttpRequest originalReq, HttpResponse returnedRes,
                                CompletableFuture<HttpResponse> future, ClientRequestContext derivedCtx,
                                @Nullable HttpResponse response,
                                @Nullable AggregatedHttpResponse aggregatedRes) {
        assert response != null || aggregatedRes != null;
        final RequestLogProperty logProperty =
                retryConfig.requiresResponseTrailers() ?
                RequestLogProperty.RESPONSE_TRAILERS : RequestLogProperty.RESPONSE_HEADERS;

        derivedCtx.log().whenAvailable(logProperty).thenAccept(log -> {
            @Nullable
            final Throwable responseCause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            if (retryConfig.needsContentInRule() && responseCause == null) {
                final RetryRuleWithContent<HttpResponse> ruleWithContent = retryConfig.retryRuleWithContent();
                assert ruleWithContent != null;
                if (aggregatedRes != null) {
                    try {
                        ruleWithContent.shouldRetry(derivedCtx, aggregatedRes.toHttpResponse(), null)
                                       .handle((decision, cause) -> {
                                           warnIfExceptionIsRaised(ruleWithContent, cause);
                                           handleRetryDecision(
                                                   decision, ctx, derivedCtx, rootReqDuplicator, originalReq,
                                                   returnedRes, future, aggregatedRes.toHttpResponse());
                                           return null;
                                       });
                    } catch (Throwable cause) {
                        handleException(ctx, rootReqDuplicator, future, cause, false);
                    }
                    return;
                }
                assert response != null;
                final HttpResponseDuplicator duplicator =
                        response.toDuplicator(derivedCtx.eventLoop().withoutContext(),
                                              derivedCtx.maxResponseLength());
                try {
                    final TruncatingHttpResponse truncatingHttpResponse =
                            new TruncatingHttpResponse(duplicator.duplicate(), retryConfig.maxContentLength());
                    final HttpResponse duplicated = duplicator.duplicate();
                    duplicator.close();
                    ruleWithContent.shouldRetry(derivedCtx, truncatingHttpResponse, null)
                                   .handle((decision, cause) -> {
                                       warnIfExceptionIsRaised(ruleWithContent, cause);
                                       truncatingHttpResponse.abort();
                                       handleRetryDecision(decision, ctx, derivedCtx, rootReqDuplicator,
                                                           originalReq, returnedRes, future, duplicated);
                                       return null;
                                   });
                } catch (Throwable cause) {
                    duplicator.abort(cause);
                    handleException(ctx, rootReqDuplicator, future, cause, false);
                }
                return;
            }
            try {
                final RetryRule retryRule = retryRule(retryConfig);
                final CompletionStage<RetryDecision> f = retryRule.shouldRetry(derivedCtx, responseCause);
                final HttpResponse response0 = aggregatedRes != null ? aggregatedRes.toHttpResponse()
                                                                     : response;
                f.handle((decision, cause) -> {
                    warnIfExceptionIsRaised(retryRule, cause);
                    handleRetryDecision(decision, ctx, derivedCtx, rootReqDuplicator,
                                        originalReq, returnedRes, future, response0);
                    return null;
                });
            } catch (Throwable cause) {
                if (response != null) {
                    response.abort(cause);
                }
                handleException(ctx, rootReqDuplicator, future, cause, false);
            }
        });
    }

    private static void warnIfExceptionIsRaised(Object retryRule, @Nullable Throwable cause) {
        if (cause != null) {
            logger.warn("Unexpected exception is raised from {}.", retryRule, cause);
        }
    }

    private static void handleException(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean endRequestLog) {
        future.completeExceptionally(cause);
        rootReqDuplicator.abort(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }

    private void handleRetryDecision(@Nullable RetryDecision decision, ClientRequestContext ctx,
                                     ClientRequestContext derivedCtx, HttpRequestDuplicator rootReqDuplicator,
                                     HttpRequest originalReq, HttpResponse returnedRes,
                                     CompletableFuture<HttpResponse> future, HttpResponse originalRes) {
        @Nullable
        final Backoff backoff = decision != null ? decision.backoff() : null;
        if (backoff != null) {
            final long millisAfter = useRetryAfter ? getRetryAfterMillis(derivedCtx) : -1;
            final long nextDelay = getNextDelay(ctx, backoff, millisAfter);
            if (nextDelay >= 0) {
                abortResponse(originalRes, derivedCtx);
                scheduleNextRetry(
                        ctx, cause -> handleException(ctx, rootReqDuplicator, future, cause, false),
                        () -> doExecute0(ctx, rootReqDuplicator, originalReq, returnedRes, future),
                        nextDelay);
                return;
            }
        }
        onRetryingComplete(ctx);
        future.complete(originalRes);
        rootReqDuplicator.close();
    }

    private static void abortResponse(HttpResponse originalRes, ClientRequestContext derivedCtx) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = derivedCtx.logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        originalRes.abort();
    }

    private static long getRetryAfterMillis(ClientRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
        @Nullable
        final String value;
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            value = log.partial().responseHeaders().get(HttpHeaderNames.RETRY_AFTER);
        } else {
            value = null;
        }

        if (value != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(value)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date date = DateFormatter.parseHttpDate(value);
                if (date != null) {
                    return date.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                         value);
        }

        return -1;
    }

    private static RetryRule retryRule(RetryConfig<HttpResponse> retryConfig) {
        if (retryConfig.needsContentInRule()) {
            return retryConfig.fromRetryRuleWithContent();
        }
        return retryConfig.retryRule();
    }
}
