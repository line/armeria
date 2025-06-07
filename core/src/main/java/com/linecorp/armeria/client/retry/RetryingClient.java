/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
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
     * less than {@code 0}
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
     * the timeout
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
     * the timeout
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
     * and {@link Request}.
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
    protected ResponseFuturePair<HttpResponse> getResponseFuturePair(
            ClientRequestContext ctx) {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(responseFuture, ctx.eventLoop());
        return new ResponseFuturePair<>(res, responseFuture);
    }

    @Override
    protected void doExecute(ClientRequestContext ctx, HttpRequest req, HttpResponse res,
                             CompletableFuture<HttpResponse> responseFuture) throws Exception {
        if (ctx.exchangeType().isRequestStreaming()) {
            final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            doExecute0(new RetryingContext(mappedRetryConfig(ctx), ctx, reqDuplicator, req, res,
                                           responseFuture));
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, cause) -> {
                   if (cause != null) {
                       handleException(ctx, null, responseFuture, cause, true);
                   } else {
                       final HttpRequestDuplicator reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       doExecute0(new RetryingContext(mappedRetryConfig(ctx), ctx, reqDuplicator, req, res,
                                                      responseFuture));
                   }
                   return null;
               });
        }
    }

    private void doExecute0(RetryingContext retryingContext) {
        final RetryConfig<HttpResponse> config = retryingContext.config();
        final ClientRequestContext ctx = retryingContext.ctx();
        final HttpRequestDuplicator rootReqDuplicator = retryingContext.reqDuplicator();
        final HttpRequest originalReq = retryingContext.req();
        final HttpResponse returnedRes = retryingContext.res();

        final int totalAttempts = getTotalAttempts(ctx);
        logger.trace("doExecute0: {}", totalAttempts);
        final boolean initialAttempt = totalAttempts <= 1;
        // The request or attemptRes has been aborted by the client before it receives a attemptRes,
        // so stop retrying.
        if (originalReq.whenComplete().isCompletedExceptionally()) {
            originalReq.whenComplete().handle((unused, cause) -> {
                handleException(retryingContext, cause, initialAttempt);
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
                handleException(retryingContext, abortCause, initialAttempt);
                return null;
            });
            return;
        }

        if (!updateResponseTimeout(ctx)) {
            handleException(retryingContext, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final HttpRequest attemptReq;
        if (initialAttempt) {
            attemptReq = rootReqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder newHeaders = originalReq.headers().toBuilder();
            newHeaders.setInt(ARMERIA_RETRY_COUNT, totalAttempts - 1);
            attemptReq = rootReqDuplicator.duplicate(newHeaders.build());
        }

        final ClientRequestContext attemptCtx;
        try {
            attemptCtx = newAttemptContext(ctx, attemptReq, ctx.rpcRequest(), initialAttempt);
        } catch (Throwable t) {
            handleException(retryingContext, t, initialAttempt);
            return;
        }

        final HttpRequest attemptCtxReq = attemptCtx.request();
        assert attemptCtxReq != null;
        final HttpResponse attemptRes;
        final ClientRequestContextExtension ctxExtension = attemptCtx.as(ClientRequestContextExtension.class);
        if (!initialAttempt && ctxExtension != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(
                    unwrap(), ctxExtension, HttpResponse::of,
                    (context, cause) -> HttpResponse.ofFailure(cause), attemptCtxReq, false);
        } else {
            attemptRes = executeWithFallback(unwrap(), attemptCtx,
                                             (context, cause) -> HttpResponse.ofFailure(cause), attemptCtxReq,
                                             false);
        }

        onAttemptStarted(ctx, attemptCtx, (@Nullable Throwable cause) -> abortAttempt(attemptCtx, attemptRes,
                                                                                      cause));

        if (!ctx.exchangeType().isResponseStreaming() || config.requiresResponseTrailers()) {
            attemptRes.aggregate().handle((attemptAggResponse, cause) -> {
                if (cause != null) {
                    attemptCtx.logBuilder().endRequest(cause);
                    attemptCtx.logBuilder().endResponse(cause);

                    // At that point we completed the request log for the attempt.
                    // What comes next investigates the attempt's response to decide
                    // on retry. We do not want to continue if we already completed
                    // the whole retrying process/ if we have a winning attempt.
                    if (isRetryingComplete(ctx)) {
                        return null;
                    }

                    handleResponseWithoutContent(retryingContext, attemptCtx,
                                                 HttpResponse.ofFailure(cause),
                                                 cause);
                } else {
                    completeAttemptLogIfBytesNotTransferred(attemptCtx, attemptAggResponse);

                    // see above
                    if (isRetryingComplete(ctx)) {
                        return null;
                    }

                    attemptCtx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                        handleAggregatedResponse(retryingContext, attemptCtx, attemptAggResponse);
                    });
                }
                return null;
            });
        } else {
            handleStreamingResponse(retryingContext, attemptCtx, attemptRes);
        }

        @Nullable
        final Backoff hedgingBackoff = config.hedgingBackoff();

        if (hedgingBackoff != null) {
            final RetrySchedulabilityDecision retrySchedulabilityDecision = canScheduleWith(ctx, hedgingBackoff,
                                                                                            -1);
            if (retrySchedulabilityDecision.canSchedule()) {
                logger.debug("Scheduling hedging with backoff: " + hedgingBackoff);
                scheduleNextRetry(ctx, () -> doExecute0(retryingContext),
                                  retrySchedulabilityDecision.nextRetryTimeNanos(),
                                  hedgingBackoff,
                                  cause -> handleException(retryingContext, cause, false));
            }
        }
    }

    private void handleResponseWithoutContent(RetryingContext retryingContext,
                                              ClientRequestContext attemptCtx, HttpResponse attemptRes,
                                              @Nullable Throwable attemptResCause) {
        if (attemptResCause != null) {
            attemptResCause = Exceptions.peel(attemptResCause);
        }

        try {
            final RetryRule retryRule = retryRule(retryingContext.config());
            final CompletionStage<RetryDecision> f = retryRule.shouldRetry(attemptCtx, attemptResCause);
            f.handle((decision, shouldRetryCause) -> {
                if (isRetryingComplete(retryingContext.ctx())) {
                    return null;
                }

                warnIfExceptionIsRaised(retryRule, shouldRetryCause);
                handleRetryDecision(retryingContext, decision, attemptCtx, attemptRes);
                return null;
            });
        } catch (Throwable cause) {
            handleException(retryingContext, cause, false);
        }
    }

    private void handleStreamingResponse(RetryingContext retryingContext,
                                         ClientRequestContext attemptCtx,
                                         HttpResponse attemptRes) {
        final SplitHttpResponse attemptSplitRes = attemptRes.split();
        attemptSplitRes.headers().handle((headers, headersCause) -> {
            final Throwable responseCause;
            if (headersCause == null) {
                final RequestLog log = attemptCtx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                responseCause = log != null ? log.responseCause() : null;
            } else {
                responseCause = Exceptions.peel(headersCause);
            }

            completeAttemptLogIfBytesNotTransferred(attemptCtx, attemptRes, headers, responseCause);

            // see above
            if (isRetryingComplete(retryingContext.ctx())) {
                attemptSplitRes.body().abort();
                return null;
            }

            attemptCtx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                // see above
                if (isRetryingComplete(retryingContext.ctx())) {
                    if (responseCause != null) {
                        attemptSplitRes.body().abort(responseCause);
                    } else {
                        attemptSplitRes.body().abort();
                    }
                    return;
                }

                if (retryingContext.config().needsContentInRule() && responseCause == null) {
                    final HttpResponse attemptUnsplitRes = HttpResponse.of(headers, attemptSplitRes.body());
                    final HttpResponseDuplicator attemptResDuplicator =
                            attemptUnsplitRes.toDuplicator(attemptCtx.eventLoop().withoutContext(),
                                                           attemptCtx.maxResponseLength());
                    try {
                        final TruncatingHttpResponse attemptTruncatedRes =
                                new TruncatingHttpResponse(attemptResDuplicator.duplicate(),
                                                           retryingContext.config().maxContentLength());
                        final HttpResponse attemptDuplicatedRes = attemptResDuplicator.duplicate();
                        attemptResDuplicator.close();

                        final RetryRuleWithContent<HttpResponse> ruleWithContent =
                                retryingContext.config().retryRuleWithContent();
                        assert ruleWithContent != null;
                        ruleWithContent.shouldRetry(attemptCtx, attemptTruncatedRes, null)
                                       .handle((decision, cause) -> {
                                           warnIfExceptionIsRaised(ruleWithContent, cause);
                                           attemptTruncatedRes.abort();

                                           if (isRetryingComplete(retryingContext.ctx())) {
                                               attemptResDuplicator.abort();
                                               return null;
                                           }

                                           handleRetryDecision(retryingContext, decision, attemptCtx,
                                                               attemptDuplicatedRes);
                                           return null;
                                       });
                    } catch (Throwable cause) {
                        attemptResDuplicator.abort(cause);
                        handleException(retryingContext, cause, false);
                    }
                } else {
                    final HttpResponse attemptUnsplitRes;
                    if (responseCause != null) {
                        attemptSplitRes.body().abort(responseCause);
                        attemptUnsplitRes = HttpResponse.ofFailure(responseCause);
                    } else {
                        attemptUnsplitRes = HttpResponse.of(headers, attemptSplitRes.body());
                    }
                    handleResponseWithoutContent(retryingContext, attemptCtx, attemptUnsplitRes, responseCause);
                }
            });
            return null;
        });
    }

    private void handleAggregatedResponse(RetryingContext retryingContext,
                                          ClientRequestContext attemptCtx,
                                          AggregatedHttpResponse attemptAggRes) {
        if (retryingContext.config().needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> ruleWithContent =
                    retryingContext.config().retryRuleWithContent();
            assert ruleWithContent != null;
            try {
                ruleWithContent.shouldRetry(attemptCtx, attemptAggRes.toHttpResponse(), null)
                               .handle((decision, cause) -> {
                                   warnIfExceptionIsRaised(ruleWithContent, cause);

                                   if (isRetryingComplete(retryingContext.ctx())) {
                                       return null;
                                   }

                                   handleRetryDecision(retryingContext,
                                                       decision, attemptCtx, attemptAggRes.toHttpResponse());
                                   return null;
                               });
            } catch (Throwable cause) {
                handleException(retryingContext, cause, false);
            }
            return;
        }

        handleResponseWithoutContent(retryingContext, attemptCtx, attemptAggRes.toHttpResponse(),
                                     null);
    }

    private static void completeAttemptLogIfBytesNotTransferred(ClientRequestContext attemptCtx,
                                                                AggregatedHttpResponse attemptAggRes) {
        if (!attemptCtx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = attemptCtx.logBuilder();
            logBuilder.endRequest();
            logBuilder.responseHeaders(attemptAggRes.headers());
            if (!attemptAggRes.trailers().isEmpty()) {
                logBuilder.responseTrailers(attemptAggRes.trailers());
            }
            logBuilder.endResponse();
        }
    }

    private static void completeAttemptLogIfBytesNotTransferred(
            ClientRequestContext attemptCtx, HttpResponse attemptRes,
            @Nullable ResponseHeaders attemptResHeaders,
            @Nullable Throwable attemptResCause) {
        if (!attemptCtx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = attemptCtx.logBuilder();
            if (attemptResCause != null) {
                logBuilder.endRequest(attemptResCause);
                logBuilder.endResponse(attemptResCause);
            } else {
                logBuilder.endRequest();
                if (attemptResHeaders != null) {
                    logBuilder.responseHeaders(attemptResHeaders);
                }
                attemptRes.whenComplete().handle((unused, cause) -> {
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

    private static void handleException(RetryingContext retryingContext, Throwable cause,
                                        boolean endRequestLog) {
        handleException(
                retryingContext.ctx(), retryingContext.reqDuplicator(), retryingContext.resFuture(),
                cause, endRequestLog);
    }

    private static void handleException(ClientRequestContext ctx,
                                        @Nullable HttpRequestDuplicator rootReqDuplicator,
                                        CompletableFuture<HttpResponse> returnedResFuture, Throwable cause,
                                        boolean endRequestLog) {
        if (isRetryingComplete(ctx)) {
            return;
        }

        if (rootReqDuplicator != null) {
            rootReqDuplicator.abort(cause);
        }

        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }

        returnedResFuture.completeExceptionally(cause);

        onRetryingCompleteExceptionally(ctx, cause);
    }

    private void handleRetryDecision(RetryingContext retryingContext, @Nullable RetryDecision decision,
                                     ClientRequestContext attemptCtx, HttpResponse attemptRes) {
        final Backoff backoff = decision != null ? decision.backoff() : null;
        final boolean shouldContinueRetry;

        if (backoff != null) {
            shouldContinueRetry = true;
            final long millisAfter = useRetryAfter ? getRetryAfterMillis(attemptCtx) : -1;
            final RetrySchedulabilityDecision schedulabilityDecision = canScheduleWith(retryingContext.ctx(),
                                                                                       backoff,
                                                                                       millisAfter);

            if (schedulabilityDecision.canSchedule()) {
                logger.debug("Scheduling next retry for {} with backoff: {}, "
                             + "schedulabilityDecision: {}",
                             retryingContext.ctx(), backoff, schedulabilityDecision);
                scheduleNextRetry(retryingContext.ctx(),
                                  () -> doExecute0(retryingContext),
                                  schedulabilityDecision.nextRetryTimeNanos(),
                                  backoff,
                                  schedulabilityDecision.earliestNextRetryTimeNanos(),
                                  cause -> handleException(retryingContext, cause, false));
            } else {
                logger.debug("Not scheduling next retry for {} with backoff: {}, "
                             + "schedulabilityDecision: {}",
                             retryingContext.ctx(), backoff, schedulabilityDecision);
                addEarliestNextRetryTimeNanos(retryingContext.ctx(),
                                              schedulabilityDecision.earliestNextRetryTimeNanos());
            }
        } else {
            shouldContinueRetry = false;
        }

        final boolean isOtherAttemptInProgress = onAttemptEnded(retryingContext.ctx());

        if (!shouldContinueRetry || !isOtherAttemptInProgress) {
            onRetryingComplete(retryingContext, attemptCtx, attemptRes);
        } else {
            logger.debug("Retrying is not complete for {} with decision: {}",
                         retryingContext.ctx(), decision);
        }
    }

    private static void abortAttempt(ClientRequestContext attemptCtx, HttpResponse attemptRes,
                                     @Nullable Throwable cause) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = attemptCtx.logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);

        if (cause != null) {
            attemptCtx.cancel(cause);
        } else {
            attemptCtx.cancel();
        }
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

    void onRetryingComplete(RetryingContext retryingContext,
                            ClientRequestContext attemptCtx,
                            HttpResponse attemptRes) {
        if (isRetryingComplete(retryingContext.ctx())) {
            return;
        }

        logger.debug("Completing retrying");

        retryingContext.reqDuplicator().close();
        retryingContext.resFuture().complete(attemptRes);
        onRetryingComplete(retryingContext.ctx(), attemptCtx);
    }

    private static class RetryingContext {
        private final ClientRequestContext ctx;
        private final HttpRequestDuplicator reqDuplicator;
        private final HttpRequest req;
        private final HttpResponse res;
        private final CompletableFuture<HttpResponse> resFuture;
        private final RetryConfig<HttpResponse> retryConfig;

        RetryingContext(RetryConfig<HttpResponse> retryConfig, ClientRequestContext ctx,
                        HttpRequestDuplicator reqDuplicator,
                        HttpRequest req, HttpResponse res,
                        CompletableFuture<HttpResponse> resFuture) {
            this.retryConfig = retryConfig;
            this.ctx = ctx;
            this.reqDuplicator = reqDuplicator;
            this.req = req;
            this.res = res;
            this.resFuture = resFuture;
        }

        RetryConfig<HttpResponse> config() {
            return retryConfig;
        }

        ClientRequestContext ctx() {
            return ctx;
        }

        HttpRequestDuplicator reqDuplicator() {
            return reqDuplicator;
        }

        HttpRequest req() {
            return req;
        }

        HttpResponse res() {
            return res;
        }

        CompletableFuture<HttpResponse> resFuture() {
            return resFuture;
        }
    }
}
