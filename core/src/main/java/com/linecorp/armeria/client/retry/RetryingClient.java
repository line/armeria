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
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

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
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;
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
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req,
                                     RetryConfig<HttpResponse> config)
            throws Exception {
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(resFuture, ctx.eventLoop());

        retryContext(ctx, req, res, resFuture, config)
                .handle((rctx, cause) -> {
                    if (cause != null) {
                        resFuture.completeExceptionally(cause);
                        ctx.logBuilder().endRequest(cause);
                        ctx.logBuilder().endResponse(cause);
                        return null;
                    }

                    // The request or attemptRes has been aborted by the client before it receives a attemptRes,
                    // so stop retrying.
                    rctx.req().whenComplete()
                        .exceptionally(reqCause -> {
                            handleException(rctx, reqCause);
                            return null;
                        });

                    rctx.res().whenComplete()
                        .handle((result, resCause) -> {
                            handleException(rctx, resCause == null ? AbortedStreamException.get() : resCause);
                            return null;
                        });

                    rctx.counter().consumeAttemptFrom(null);
                    retry(rctx);

                    return null;
                });

        return res;
    }

    private void retry(HttpRetryContext rctx) {
        if (isRetryCompleted(rctx)) {
            return;
        }

        if (!rctx.setResponseTimeout()) {
            handleException(rctx, ResponseTimeoutException.get());
            return;
        }

        final RetryAttempt<HttpResponse> attempt;
        try {
            attempt = executeAttempt(rctx);
        } catch (Throwable cause) {
            handleException(rctx, cause);
            return;
        }

        if (rctx.ctx().exchangeType().isResponseStreaming() && !rctx.config().requiresResponseTrailers()) {
            handleStreamingAttemptResponse(rctx, attempt);
        } else {
            handleAggregatedAttemptResponse(rctx, attempt);
        }
    }

    private void handleAggregatedAttemptResponse(HttpRetryContext rctx, RetryAttempt<HttpResponse> attempt) {
        attempt.res().aggregate().handle((aggAttemptRes, cause) -> {
            if (cause != null) {
                attempt.ctx().logBuilder().endRequest(cause);
                attempt.ctx().logBuilder().endResponse(cause);
                decideAndHandleDecision(rctx, attempt.setRes(HttpResponse.ofFailure(cause)),
                                        HttpResponse.ofFailure(cause), null);
            } else {
                completeLogIfBytesNotTransferred(attempt.ctx(), aggAttemptRes);
                attempt.ctx().log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                    decideAndHandleDecision(rctx, attempt.setRes(aggAttemptRes.toHttpResponse()),
                                            aggAttemptRes.toHttpResponse(), null);
                });
            }
            return null;
        });
    }

    private void decideAndHandleDecision(HttpRetryContext rctx,
                                         RetryAttempt<HttpResponse> attempt,
                                         @Nullable HttpResponse resToDecide,
                                         @Nullable Throwable causeToDecide) {
        decide(rctx, attempt, resToDecide, causeToDecide)
                .handle((decision, decisionCause) -> {
                    if (resToDecide != null) {
                        // resToDecide.abort();
                    }

                    if (decisionCause != null) {
                        abortAttempt(attempt);
                        handleException(rctx, decisionCause);
                    } else {
                        handleDecision(rctx, attempt, decision);
                    }
                    return null;
                });
    }

    private CompletionStage<RetryDecision> decide(HttpRetryContext rctx,
                                                  RetryAttempt<HttpResponse> attempt,
                                                  @Nullable HttpResponse resToDecide,
                                                  @Nullable Throwable causeToDecide) {
        if (causeToDecide != null) {
            causeToDecide = Exceptions.peel(causeToDecide);
        }

        try {
            if (rctx.config().needsContentInRule()) {
                assert resToDecide != null ^ causeToDecide != null;
                final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                        rctx.config().retryRuleWithContent();
                assert retryRuleWithContent != null;
                return retryRuleWithContent
                        .shouldRetry(attempt.ctx(), resToDecide, causeToDecide)
                        .handle((decision, cause) -> {
                            warnIfExceptionIsRaised(retryRuleWithContent, cause);
                            return decision;
                        });
            } else {
                final RetryRule retryRuleWithoutContent = rctx.config().retryRule();
                assert retryRuleWithoutContent != null;
                return retryRuleWithoutContent
                        .shouldRetry(attempt.ctx(), causeToDecide)
                        .handle((decision, cause) -> {
                            warnIfExceptionIsRaised(retryRuleWithoutContent, cause);
                            return decision;
                        });
            }
        } catch (Throwable ruleCause) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(ruleCause);
        }
    }

    private void handleDecision(HttpRetryContext rctx, RetryAttempt<HttpResponse> attempt,
                                @Nullable RetryDecision decision) {
        final Backoff backoff = decision != null ? decision.backoff() : null;
        if (backoff != null) {
            final long millisAfter = useRetryAfter ? getRetryAfterMillis(attempt.ctx()) : -1;
            final long nextDelay = getNextDelay(rctx, backoff, millisAfter);
            if (nextDelay >= 0) {
                abortAttempt(attempt);
                scheduleNextRetry(
                        rctx.ctx(), scheduleCause -> handleException(rctx, scheduleCause),
                        () -> retry(rctx),
                        nextDelay);
                return;
            }
        }
        onRetryingComplete(rctx.ctx());
        rctx.resFuture().complete(attempt.res());
        rctx.requestDuplicator().close();
    }

    private RetryAttempt<HttpResponse> executeAttempt(HttpRetryContext rctx) {
        final boolean isInitialAttempt = rctx.counter().numberAttemptsSoFar() <= 1;
        final HttpRequest duplicateReq;
        if (isInitialAttempt) {
            duplicateReq = rctx.requestDuplicator().duplicate();
        } else {
            final RequestHeadersBuilder newHeaders = rctx.req().headers().toBuilder();
            newHeaders.setInt(ClientUtil.ARMERIA_RETRY_COUNT, rctx.counter().numberAttemptsSoFar() - 1);
            duplicateReq = rctx.requestDuplicator().duplicate(newHeaders.build());
        }

        final ClientRequestContext attemptCtx;

        attemptCtx = newDerivedContext(rctx.ctx(), duplicateReq, rctx.ctx().rpcRequest(), isInitialAttempt);

        final HttpRequest attemptReq = attemptCtx.request();
        assert attemptReq != null;
        final HttpResponse attemptRes;
        final ClientRequestContextExtension ctxExtension = attemptCtx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && ctxExtension != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(
                    unwrap(), ctxExtension, HttpResponse::of,
                    (context, cause) -> HttpResponse.ofFailure(cause), attemptReq, false);
        } else {
            attemptRes = executeWithFallback(unwrap(), attemptCtx,
                                             (context, cause) -> HttpResponse.ofFailure(cause), attemptReq,
                                             false);
        }

        return new RetryAttempt<>(attemptCtx, attemptRes);
    }

    private void handleStreamingAttemptResponse(HttpRetryContext rctx,
                                                RetryAttempt<HttpResponse> attempt) {
        final SplitHttpResponse splitAttemptRes = attempt.res().split();
        splitAttemptRes.headers().handle((headers, headersCause) -> {
            final Throwable responseCause;
            if (headersCause == null) {
                final RequestLog log = attempt.ctx().log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                responseCause = log != null ? log.responseCause() : null;
            } else {
                responseCause = Exceptions.peel(headersCause);
            }
            completeLogIfBytesNotTransferred(attempt.ctx(), headers, responseCause, attempt.res());

            attempt.ctx().log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                if (rctx.config().needsContentInRule() && responseCause == null) {
                    final HttpResponse unsplitAttemptRes = splitAttemptRes.unsplit();
                    final HttpResponseDuplicator attemptResDuplicator =
                            unsplitAttemptRes.toDuplicator(attempt.ctx().eventLoop().withoutContext(),
                                                           attempt.ctx().maxResponseLength());
                    try {
                        final RetryAttempt<HttpResponse> attemptWithResHeaders = attempt.setRes(
                                attemptResDuplicator.duplicate());
                        final TruncatingHttpResponse truncatingAttemptRes =
                                new TruncatingHttpResponse(attemptResDuplicator.duplicate(),
                                                           rctx.config().maxContentLength());
                        attemptResDuplicator.close();

                        decideAndHandleDecision(rctx, attemptWithResHeaders, truncatingAttemptRes,
                                                null);
                    } catch (Throwable cause) {
                        attemptResDuplicator.abort(cause);
                    }
                } else {
                    if (responseCause != null) {
                        splitAttemptRes.body().abort(responseCause);
                        decideAndHandleDecision(
                                rctx, attempt.setRes(HttpResponse.ofFailure(responseCause)), null,
                                responseCause
                        );
                    } else {
                        decideAndHandleDecision(rctx,
                                                attempt.setRes(splitAttemptRes.unsplit()),
                                                null,
                                                null);
                    }
                }
            });
            return null;
        });
    }

    private CompletableFuture<HttpRetryContext> retryContext(ClientRequestContext ctx, HttpRequest req,
                                                             HttpResponse res,
                                                             CompletableFuture<HttpResponse> resFuture,
                                                             RetryConfig<HttpResponse> config) {

        if (ctx.exchangeType().isRequestStreaming()) {
            return UnmodifiableFuture.completedFuture(new HttpRetryContext(
                    ctx, req, req.toDuplicator(ctx.eventLoop().withoutContext(), 0), res, resFuture, config,
                    ctx.responseTimeoutMillis()
            ));
        } else {
            return req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
                      .thenApply(AggregatedHttpRequestDuplicator::new)
                      .thenApply(reqDuplicator -> new HttpRetryContext(
                              ctx, req, reqDuplicator, res, resFuture,
                              config, ctx.responseTimeoutMillis()
                      ));
        }
    }

    private boolean isRetryCompleted(HttpRetryContext rctx) {
        return rctx.ctx().isCancelled() || rctx.req().whenComplete().isCompletedExceptionally() ||
               rctx.res()
                   .whenComplete()
                   .isDone();
    }

    private static void completeLogIfBytesNotTransferred(
            ClientRequestContext ctx, AggregatedHttpResponse res) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            logBuilder.endRequest();
            logBuilder.responseHeaders(res.headers());
            if (!res.trailers().isEmpty()) {
                logBuilder.responseTrailers(res.trailers());
            }
            logBuilder.endResponse();
        }
    }

    private static void completeLogIfBytesNotTransferred(
            ClientRequestContext ctx,
            @Nullable ResponseHeaders headers, @Nullable Throwable resCause, HttpResponse res) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (resCause != null) {
                logBuilder.endRequest(resCause);
                logBuilder.endResponse(resCause);
            } else {
                logBuilder.endRequest();
                if (headers != null) {
                    logBuilder.responseHeaders(headers);
                }
                res.whenComplete().handle((unused, cause) -> {
                    if (cause != null) {
                        logBuilder.endResponse(cause);
                    } else {
                        logBuilder.endResponse();
                    }
                    return null;
                });
            }
        }
    }

    private static void warnIfExceptionIsRaised(Object retryRule, @Nullable Throwable cause) {
        if (cause != null) {
            logger.warn("Unexpected exception is raised from {}.", retryRule, cause);
        }
    }

    private static void handleException(HttpRetryContext rctx, Throwable cause) {
        rctx.resFuture().completeExceptionally(cause);
        rctx.requestDuplicator().abort(cause);
        if (!rctx.ctx().logBuilder().isRequestComplete()) {
            rctx.ctx().logBuilder().endRequest(cause);
        }
        rctx.ctx().logBuilder().endResponse(cause);
    }

    private static void abortAttempt(RetryAttempt<HttpResponse> attempt) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = attempt.ctx().logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        attempt.res().abort();
    }

    private static long getRetryAfterMillis(ClientRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
        final String value;
        final RequestLog requestLog = log.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        value = requestLog != null ? requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

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
        } else {
            final RetryRule rule = retryConfig.retryRule();
            assert rule != null;
            return rule;
        }
    }

    private static final class HttpRetryContext extends RetryContext<HttpRequest, HttpResponse> {
        private final HttpRequestDuplicator requestDuplicator;

        HttpRetryContext(ClientRequestContext ctx, HttpRequest req,
                         HttpRequestDuplicator requestDuplicator,
                         HttpResponse res, CompletableFuture<HttpResponse> resFuture,
                         RetryConfig<HttpResponse> config, long responseTimeoutMillis) {
            super(ctx, req, res, resFuture, config, responseTimeoutMillis);
            this.requestDuplicator = requestDuplicator;
        }

        HttpRequestDuplicator requestDuplicator() {
            return requestDuplicator;
        }
    }
}
