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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeadersBuilder;
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
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRule}.
     */
    public static RetryingClientBuilder builder(RetryRule retryRule) {
        return new RetryingClientBuilder(retryRule);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryingClientBuilder(retryRuleWithContent);
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
        return new RetryingClientBuilder(retryRuleWithContent, maxContentLength);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingClientBuilder builder(RetryConfigMapping<HttpResponse> mapping) {
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
     */
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
     */
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
     */
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
     */
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
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given context/request.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryConfigMapping<HttpResponse> mapping) {
        return builder(mapping).newDecorator();
    }

    private final boolean useRetryAfter;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RetryingClient(
            HttpClient delegate, RetryConfigMapping<HttpResponse> mapping, boolean useRetryAfter) {
        super(delegate, mapping);
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
                final Throwable abortCause = firstNonNull(cause, AbortedStreamException.get());
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

        final ClientRequestContext derivedCtx = newDerivedContext(ctx, duplicateReq, ctx.rpcRequest(),
                                                                  initialAttempt);
        ctx.logBuilder().addChild(derivedCtx.log());

        final HttpResponse response = executeWithFallback(unwrap(), derivedCtx,
                                                          (context, cause) -> HttpResponse.ofFailure(cause));

        final RetryConfig<HttpResponse> config = mapping().get(ctx, originalReq);
        if (config.requiresResponseTrailers()) {
            response.aggregate().handle((aggregated, cause) -> {
                handleResponse(config, ctx, rootReqDuplicator, originalReq, returnedRes, future, derivedCtx,
                               cause != null ? HttpResponse.ofFailure(cause) : aggregated.toHttpResponse());
                return null;
            });
        } else {
            handleResponse(
                    config, ctx, rootReqDuplicator, originalReq, returnedRes, future, derivedCtx, response);
        }
    }

    private void handleResponse(RetryConfig<HttpResponse> retryConfig, ClientRequestContext ctx,
                                HttpRequestDuplicator rootReqDuplicator,
                                HttpRequest originalReq, HttpResponse returnedRes,
                                CompletableFuture<HttpResponse> future, ClientRequestContext derivedCtx,
                                HttpResponse response) {

        final RequestLogProperty logProperty =
                retryConfig.requiresResponseTrailers() ?
                RequestLogProperty.RESPONSE_TRAILERS : RequestLogProperty.RESPONSE_HEADERS;

        derivedCtx.log().whenAvailable(logProperty).thenAccept(log -> {
            final Throwable responseCause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            if (retryConfig.needsContentInRule() && responseCause == null) {
                final HttpResponseDuplicator duplicator =
                        response.toDuplicator(derivedCtx.eventLoop().withoutContext(),
                                              derivedCtx.maxResponseLength());
                try {
                    final TruncatingHttpResponse truncatingHttpResponse =
                            new TruncatingHttpResponse(duplicator.duplicate(), retryConfig.maxContentLength());
                    final HttpResponse duplicated = duplicator.duplicate();
                    retryConfig.retryRuleWithContent()
                               .shouldRetry(derivedCtx, truncatingHttpResponse, null)
                               .handle((decision, cause) -> {
                                   truncatingHttpResponse.abort();
                                   return handleBackoff(
                                           ctx, derivedCtx, rootReqDuplicator, originalReq, returnedRes,
                                           future,duplicated, duplicator::abort)
                                           .apply(decision, cause);
                               });
                    duplicator.close();
                } catch (Throwable cause) {
                    duplicator.abort(cause);
                    handleException(ctx, rootReqDuplicator, future, cause, false);
                }
            } else {
                try {
                    final RetryRule retryRule;
                    if (retryConfig.needsContentInRule()) {
                        retryRule = retryConfig.fromRetryRuleWithContent();
                    } else {
                        retryRule = retryConfig.retryRule();
                    }

                    final CompletionStage<RetryDecision> f = retryRule.shouldRetry(derivedCtx, responseCause);

                    final Runnable originalResClosingTask =
                            responseCause == null ? response::abort
                                                  : () -> response.abort(responseCause);

                    f.handle(handleBackoff(ctx, derivedCtx, rootReqDuplicator,
                                           originalReq, returnedRes, future, response,
                                           originalResClosingTask));
                } catch (Throwable cause) {
                    response.abort(cause);
                    handleException(ctx, rootReqDuplicator, future, cause, false);
                }
            }
        });
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

    private BiFunction<RetryDecision, Throwable, Void> handleBackoff(
            ClientRequestContext ctx, ClientRequestContext derivedCtx, HttpRequestDuplicator rootReqDuplicator,
            HttpRequest originalReq, HttpResponse returnedRes, CompletableFuture<HttpResponse> future,
            HttpResponse originalRes, Runnable originalResClosingTask) {
        return (decision, unused) -> {
            final Backoff backoff = decision != null ? decision.backoff() : null;
            if (backoff != null) {
                // Set response content with null to make sure that the log is complete.
                final RequestLogBuilder logBuilder = derivedCtx.logBuilder();
                logBuilder.responseContent(null, null);
                logBuilder.responseContentPreview(null);

                final long millisAfter = useRetryAfter ? getRetryAfterMillis(derivedCtx) : -1;
                final long nextDelay = getNextDelay(ctx, backoff, millisAfter);
                if (nextDelay >= 0) {
                    originalResClosingTask.run();
                    scheduleNextRetry(
                            ctx, cause -> handleException(ctx, rootReqDuplicator, future, cause, false),
                            () -> doExecute0(ctx, rootReqDuplicator, originalReq, returnedRes, future),
                            nextDelay);
                    return null;
                }
            }
            onRetryingComplete(ctx);
            future.complete(originalRes);
            rootReqDuplicator.close();
            return null;
        };
    }

    private static long getRetryAfterMillis(ClientRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
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
}
