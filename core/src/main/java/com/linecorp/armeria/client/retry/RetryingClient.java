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
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(resFuture, ctx.eventLoop());
        if (ctx.exchangeType().isRequestStreaming()) {
            final HttpRequestDuplicator reqDuplicator =
                    req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            final RetryingContext retryingContext = new RetryingContext(
                    ctx, mappedRetryConfig(ctx), req, reqDuplicator, res, resFuture);
            doExecute0(retryingContext);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, cause) -> {
                   if (cause != null) {
                       handleException(ctx, null, resFuture, cause, true);
                   } else {
                       final HttpRequestDuplicator reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       final RetryingContext retryingContext = new RetryingContext(ctx, mappedRetryConfig(ctx),
                                                                                   req, reqDuplicator, res,
                       resFuture);
                       doExecute0(retryingContext);
                   }
                   return null;
               });
        }
        return res;
    }

    private void doExecute0(RetryingContext retryingContext) {
        final RetryConfig<HttpResponse> config = retryingContext.config();
        final ClientRequestContext ctx = retryingContext.ctx();
        final HttpRequestDuplicator reqDuplicator = retryingContext.reqDuplicator();
        final HttpRequest req = retryingContext.req();
        final HttpResponse res = retryingContext.res();

        final int attemptNo = getTotalAttempts(ctx);
        final boolean isInitialAttempt = attemptNo <= 1;
        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (req.whenComplete().isCompletedExceptionally()) {
            req.whenComplete().handle((unused, cause) -> {
                handleException(retryingContext, cause, isInitialAttempt);
                return null;
            });
            return;
        }
        if (res.isComplete()) {
            res.whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                handleException(retryingContext, abortCause, isInitialAttempt);
                return null;
            });
            return;
        }

        if (!setResponseTimeout(ctx)) {
            handleException(retryingContext, ResponseTimeoutException.get(), isInitialAttempt);
            return;
        }

        final HttpRequest attemptReq;
        if (isInitialAttempt) {
            attemptReq = reqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNo - 1);
            attemptReq = reqDuplicator.duplicate(attemptHeadersBuilder.build());
        }

        final ClientRequestContext attemptCtx;
        try {
            attemptCtx = newAttemptContext(ctx, attemptReq, ctx.rpcRequest(), isInitialAttempt);
        } catch (Throwable t) {
            handleException(retryingContext, t, isInitialAttempt);
            return;
        }

        final HttpResponse attemptRes = executeAttempt(attemptCtx, isInitialAttempt);

        if (!ctx.exchangeType().isResponseStreaming() || config.requiresResponseTrailers()) {
            attemptRes.aggregate().handle((aggregatedAttemptRes, cause) -> {
                if (cause != null) {
                    attemptCtx.logBuilder().endRequest(cause);
                    attemptCtx.logBuilder().endResponse(cause);
                    handleResponseWithoutContent(
                            retryingContext, attemptCtx, HttpResponse.ofFailure(cause), cause);
                } else {
                    completeLogIfBytesNotTransferred(aggregatedAttemptRes, attemptCtx);
                    attemptCtx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                        handleAggregatedResponse(retryingContext, attemptCtx, aggregatedAttemptRes);
                    });
                }
                return null;
            });
        } else {
            handleStreamingResponse(retryingContext, attemptCtx, attemptRes);
        }
    }

    private HttpResponse executeAttempt(ClientRequestContext attemptCtx, boolean isInitialAttempt) {
        final HttpRequest attemptReq = attemptCtx.request();
        assert attemptReq != null;
        final HttpResponse attemptRes;
        final ClientRequestContextExtension attemptCtxExtension =
                attemptCtx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && attemptCtxExtension != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(
                    unwrap(), attemptCtxExtension, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), attemptReq, false);
        } else {
            attemptRes = executeWithFallback(unwrap(), attemptCtx,
                                             (unused, cause) ->
                                                   HttpResponse.ofFailure(cause), attemptReq, false);
        }
        return attemptRes;
    }

    // TODO(ikhoon): Add a request-scope class such as RetryRequestContext to avoid passing too many parameters.
    private void handleResponseWithoutContent(RetryingContext retryingContext,
                                              ClientRequestContext attemptCtx,
                                              HttpResponse attemptResWithoutContent,
                                              @Nullable Throwable attemptResCause) {
        if (attemptResCause != null) {
            attemptResCause = Exceptions.peel(attemptResCause);
        }
        try {
            final RetryRule retryRule = retryRule(retryingContext.config());
            final CompletionStage<RetryDecision> f = retryRule.shouldRetry(attemptCtx, attemptResCause);
            f.handle((decision, shouldRetryCause) -> {
                warnIfExceptionIsRaised(retryRule, shouldRetryCause);
                handleRetryDecision(retryingContext, decision, attemptCtx, attemptResWithoutContent);
                return null;
            });
        } catch (Throwable cause) {
            attemptResWithoutContent.abort();
            handleException(retryingContext, cause, false);
        }
    }

    private void handleStreamingResponse(RetryingContext retryingContext,
                                         ClientRequestContext attemptCtx,
                                         HttpResponse attemptRes) {
        final RetryConfig<HttpResponse> retryConfig = retryingContext.config();

        final SplitHttpResponse splitAttemptRes = attemptRes.split();
        splitAttemptRes.headers().handle((attemptResHeaders, headersCause) -> {
            final Throwable attemptResCause;
            if (headersCause == null) {
                final RequestLog log = attemptCtx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                attemptResCause = log != null ? log.responseCause() : null;
            } else {
                attemptResCause = Exceptions.peel(headersCause);
            }
            completeLogIfBytesNotTransferred(attemptCtx, attemptRes, attemptResHeaders, attemptResCause);

            attemptCtx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                if (retryConfig.needsContentInRule() && attemptResCause == null) {
                    final HttpResponse unsplitAttemptRes = splitAttemptRes.unsplit();
                    final HttpResponseDuplicator attemptResDuplicator =
                            unsplitAttemptRes.toDuplicator(attemptCtx.eventLoop().withoutContext(),
                                                   attemptCtx.maxResponseLength());
                    try {
                        final TruncatingHttpResponse truncatingAttemptRes =
                                new TruncatingHttpResponse(attemptResDuplicator.duplicate(),
                                                           retryConfig.maxContentLength());
                        final HttpResponse duplicatedAttemptRes = attemptResDuplicator.duplicate();
                        attemptResDuplicator.close();

                        final RetryRuleWithContent<HttpResponse> ruleWithContent =
                                retryConfig.retryRuleWithContent();
                        assert ruleWithContent != null;
                        ruleWithContent.shouldRetry(attemptCtx, truncatingAttemptRes, null)
                                       .handle((decision, cause) -> {
                                           warnIfExceptionIsRaised(ruleWithContent, cause);
                                           truncatingAttemptRes.abort();
                                           handleRetryDecision(
                                                   retryingContext,
                                                   decision, attemptCtx, duplicatedAttemptRes);
                                           return null;
                                       });
                    } catch (Throwable cause) {
                        attemptResDuplicator.abort(cause);
                        handleException(retryingContext, cause, false);
                    }
                } else {
                    final HttpResponse unsplitAttemptRes;
                    if (attemptResCause != null) {
                        splitAttemptRes.body().abort(attemptResCause);
                        unsplitAttemptRes = HttpResponse.ofFailure(attemptResCause);
                    } else {
                        unsplitAttemptRes = splitAttemptRes.unsplit();
                    }
                    handleResponseWithoutContent(retryingContext,
                                                 attemptCtx, unsplitAttemptRes, attemptResCause);
                }
            });
            return null;
        });
    }

    private void handleAggregatedResponse(RetryingContext retryingContext,
                                          ClientRequestContext attemptCtx,
                                          AggregatedHttpResponse aggregatedAttemptRes) {
        final RetryConfig<HttpResponse> retryConfig = retryingContext.config();

        if (retryConfig.needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> ruleWithContent = retryConfig.retryRuleWithContent();
            assert ruleWithContent != null;
            try {
                ruleWithContent.shouldRetry(attemptCtx, aggregatedAttemptRes.toHttpResponse(), null)
                               .handle((decision, cause) -> {
                                   warnIfExceptionIsRaised(ruleWithContent, cause);
                                   handleRetryDecision(retryingContext,
                                           decision, attemptCtx, aggregatedAttemptRes.toHttpResponse());
                                   return null;
                               });
            } catch (Throwable cause) {
                handleException(retryingContext, cause, false);
            }
            return;
        }
        handleResponseWithoutContent(retryingContext, attemptCtx, aggregatedAttemptRes.toHttpResponse(), null);
    }

    private static void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggregatedAttemptRes,
                                                         ClientRequestContext attemptCtx) {
        if (!attemptCtx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder attemptLogBuilder = attemptCtx.logBuilder();
            attemptLogBuilder.endRequest();
            attemptLogBuilder.responseHeaders(aggregatedAttemptRes.headers());
            if (!aggregatedAttemptRes.trailers().isEmpty()) {
                attemptLogBuilder.responseTrailers(aggregatedAttemptRes.trailers());
            }
            attemptLogBuilder.endResponse();
        }
    }

    private static void completeLogIfBytesNotTransferred(
            ClientRequestContext attemptCtx, HttpResponse attemptRes, @Nullable ResponseHeaders attemptHeaders,
            @Nullable Throwable responseCause) {
        if (!attemptCtx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = attemptCtx.logBuilder();
            if (responseCause != null) {
                logBuilder.endRequest(responseCause);
                logBuilder.endResponse(responseCause);
            } else {
                logBuilder.endRequest();
                if (attemptHeaders != null) {
                    logBuilder.responseHeaders(attemptHeaders);
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

    private static void handleException(RetryingContext retryingContext,
                                        Throwable cause,
                                        boolean endRequestLog) {
        handleException(
                retryingContext.ctx(), retryingContext.reqDuplicator(), retryingContext.resFuture(), cause,
                endRequestLog);
    }

    private static void handleException(ClientRequestContext ctx,
                                        @Nullable HttpRequestDuplicator reqDuplicator,
                                        CompletableFuture<HttpResponse> resFuture, Throwable cause,
                                        boolean endRequestLog) {
        resFuture.completeExceptionally(cause);
        if (reqDuplicator != null) {
            reqDuplicator.abort(cause);
        }
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }

    private void handleRetryDecision(RetryingContext retryingContext, @Nullable RetryDecision decision,
                                     ClientRequestContext attemptCtx, HttpResponse attemptRes) {
        final Backoff backoff = decision != null ? decision.backoff() : null;
        if (backoff != null) {
            final long millisAfter = useRetryAfter ? getRetryAfterMillis(attemptCtx) : -1;
            final long nextDelay = getNextDelay(retryingContext.ctx(), backoff, millisAfter);
            if (nextDelay >= 0) {
                abortResponse(attemptRes, attemptCtx);
                scheduleNextRetry(
                        retryingContext.ctx(), cause -> handleException(retryingContext, cause, false),
                        () -> doExecute0(retryingContext),
                        nextDelay);
                return;
            }
        }
        onRetryingComplete(retryingContext.ctx());
        retryingContext.resFuture().complete(attemptRes);
        retryingContext.reqDuplicator().close();
    }

    private static void abortResponse(HttpResponse attemptRes, ClientRequestContext attemptCtx) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = attemptCtx.logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        attemptRes.abort();
    }

    private static long getRetryAfterMillis(ClientRequestContext attemptCtx) {
        final RequestLogAccess attemptLog = attemptCtx.log();
        final String retryAfterValue;
        final RequestLog requestLog = attemptLog.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        retryAfterValue = requestLog != null ?
                          requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

        if (retryAfterValue != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(retryAfterValue)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date retryAfterDate = DateFormatter.parseHttpDate(retryAfterValue);
                if (retryAfterDate != null) {
                    return retryAfterDate.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                         retryAfterValue);
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

    private static class RetryingContext {
        private final ClientRequestContext ctx;
        private final RetryConfig<HttpResponse> retryConfig;
        private final HttpRequest req;
        private final HttpRequestDuplicator reqDuplicator;
        private final HttpResponse res;
        private final CompletableFuture<HttpResponse> resFuture;

        RetryingContext(ClientRequestContext ctx, RetryConfig<HttpResponse> retryConfig,
                        HttpRequest req, HttpRequestDuplicator reqDuplicator,
                        HttpResponse res,
                        CompletableFuture<HttpResponse> resFuture) {
            this.ctx = ctx;
            this.retryConfig = retryConfig;
            this.req = req;
            this.reqDuplicator = reqDuplicator;
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
