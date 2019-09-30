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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.internal.ClientUtil.executeWithFallback;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.handler.codec.DateFormatter;

/**
 * A {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingHttpClient extends RetryingClient<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(RetryingHttpClient.class);

    /**
     * Returns a new {@link RetryingHttpClientBuilder} with the specified {@link RetryStrategy}.
     */
    public static RetryingHttpClientBuilder builder(RetryStrategy retryStrategy) {
        return new RetryingHttpClientBuilder(retryStrategy);
    }

    /**
     * Returns a new {@link RetryingHttpClientBuilder} with the specified {@link RetryStrategyWithContent}.
     */
    public static RetryingHttpClientBuilder builder(
            RetryStrategyWithContent<HttpResponse> retryStrategyWithContent) {
        return new RetryingHttpClientBuilder(retryStrategyWithContent);
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy retryStrategy) {
        return RetryingHttpClient.builder(retryStrategy)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy retryStrategy, int maxTotalAttempts) {
        return RetryingHttpClient.builder(retryStrategy)
                                 .maxTotalAttempts(maxTotalAttempts)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy retryStrategy,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return RetryingHttpClient.builder(retryStrategy)
                                 .maxTotalAttempts(maxTotalAttempts)
                                 .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                 .newDecorator();
    }

    private final boolean useRetryAfter;

    private final int contentPreviewLength;

    private final boolean needsContentInStrategy;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                       RetryStrategy retryStrategy, int totalMaxAttempts,
                       long responseTimeoutMillisForEachAttempt, boolean useRetryAfter) {
        super(delegate, retryStrategy, totalMaxAttempts, responseTimeoutMillisForEachAttempt);
        needsContentInStrategy = false;
        this.useRetryAfter = useRetryAfter;
        contentPreviewLength = 0;
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                       RetryStrategyWithContent<HttpResponse> retryStrategyWithContent, int totalMaxAttempts,
                       long responseTimeoutMillisForEachAttempt, boolean useRetryAfter,
                       int contentPreviewLength) {
        super(delegate, retryStrategyWithContent, totalMaxAttempts, responseTimeoutMillisForEachAttempt);
        needsContentInStrategy = true;
        this.useRetryAfter = useRetryAfter;
        checkArgument(contentPreviewLength > 0,
                      "contentPreviewLength: %s (expected: > 0)", contentPreviewLength);
        this.contentPreviewLength = contentPreviewLength;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final boolean hasInitialAuthority = !isNullOrEmpty(req.headers().authority());
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture, ctx.eventLoop());
        final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(req, 0, ctx.eventLoop());
        doExecute0(ctx, reqDuplicator, req, res, responseFuture, hasInitialAuthority);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                            HttpRequest originalReq, HttpResponse returnedRes,
                            CompletableFuture<HttpResponse> future, boolean hasInitialAuthority) {
        final int totalAttempts = getTotalAttempts(ctx);
        final boolean initialAttempt = totalAttempts <= 1;
        if (originalReq.completionFuture().isCompletedExceptionally() || returnedRes.isComplete()) {
            // The request or response has been aborted by the client before it receives a response,
            // so stop retrying.
            handleException(ctx, rootReqDuplicator, future, AbortedStreamException.get(), initialAttempt);
            return;
        }

        if (!setResponseTimeout(ctx)) {
            handleException(ctx, rootReqDuplicator, future, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final HttpRequest duplicateReq;
        if (hasInitialAuthority && initialAttempt) {
            duplicateReq = rootReqDuplicator.duplicateStream();
        } else {
            final RequestHeadersBuilder newHeaders = originalReq.headers().toBuilder();
            if (!hasInitialAuthority) {
                newHeaders.remove(HttpHeaderNames.AUTHORITY);
            }
            if (totalAttempts > 1) {
                newHeaders.setInt(ARMERIA_RETRY_COUNT, totalAttempts - 1);
            }
            duplicateReq = rootReqDuplicator.duplicateStream(newHeaders.build());
        }

        final ClientRequestContext derivedCtx = newDerivedContext(ctx, duplicateReq, initialAttempt);
        ctx.logBuilder().addChild(derivedCtx.log());

        final HttpResponse response = executeWithFallback(delegate(), derivedCtx,
                                                          (context, cause) -> HttpResponse.ofFailure(cause));

        derivedCtx.log().addListener(log -> {
            if (needsContentInStrategy) {
                final HttpResponseDuplicator resDuplicator = new HttpResponseDuplicator(
                        response, maxSignalLength(derivedCtx.maxResponseLength()), derivedCtx.eventLoop());
                retryStrategyWithContent().shouldRetry(derivedCtx, contentPreviewResponse(resDuplicator))
                                          .handle(handleBackoff(ctx, derivedCtx, rootReqDuplicator,
                                                                originalReq, returnedRes, future,
                                                                resDuplicator.duplicateStream(true),
                                                                resDuplicator::close,
                                                                hasInitialAuthority));
            } else {
                final Throwable responseCause =
                        log.isAvailable(RequestLogAvailability.RESPONSE_END) ? log.responseCause() : null;
                retryStrategy().shouldRetry(derivedCtx, responseCause)
                               .handle(handleBackoff(ctx, derivedCtx, rootReqDuplicator, originalReq,
                                                     returnedRes, future, response, response::abort,
                                                     hasInitialAuthority));
            }
        }, RequestLogAvailability.RESPONSE_HEADERS);
    }

    private static void handleException(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean endRequestLog) {
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
        future.completeExceptionally(cause);
        rootReqDuplicator.close();
    }

    private static int maxSignalLength(long maxResponseLength) {
        if (maxResponseLength == 0 || maxResponseLength > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxResponseLength;
    }

    private ContentPreviewResponse contentPreviewResponse(HttpResponseDuplicator resDuplicator) {
        return new ContentPreviewResponse(resDuplicator.duplicateStream(), contentPreviewLength);
    }

    private BiFunction<Backoff, Throwable, Void> handleBackoff(ClientRequestContext ctx,
                                                               ClientRequestContext derivedCtx,
                                                               HttpRequestDuplicator rootReqDuplicator,
                                                               HttpRequest originalReq,
                                                               HttpResponse returnedRes,
                                                               CompletableFuture<HttpResponse> future,
                                                               HttpResponse originalRes,
                                                               Runnable closingOriginalResTask,
                                                               boolean hasInitialAuthority) {
        return (backoff, unused) -> {
            if (backoff != null) {
                final long millisAfter = useRetryAfter ? getRetryAfterMillis(derivedCtx) : -1;
                final long nextDelay = getNextDelay(ctx, backoff, millisAfter);
                if (nextDelay >= 0) {
                    closingOriginalResTask.run();
                    scheduleNextRetry(
                            ctx, cause -> handleException(ctx, rootReqDuplicator, future, cause, false),
                            () -> doExecute0(ctx, rootReqDuplicator, originalReq,
                                             returnedRes, future, hasInitialAuthority),
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
        final HttpHeaders headers = firstNonNull(ctx.log().responseHeaders(), HttpHeaders.of());
        final String value = headers.get(HttpHeaderNames.RETRY_AFTER);
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

    private static class ContentPreviewResponse extends FilteredHttpResponse {

        private final int contentPreviewLength;
        private int contentLength;
        @Nullable
        private Subscription subscription;

        ContentPreviewResponse(HttpResponse delegate, int contentPreviewLength) {
            super(delegate);
            this.contentPreviewLength = contentPreviewLength;
        }

        @Override
        protected void beforeSubscribe(Subscriber<? super HttpObject> subscriber, Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        protected HttpObject filter(HttpObject obj) {
            if (obj instanceof HttpData) {
                final int dataLength = ((HttpData) obj).length();
                contentLength += dataLength;
                if (contentLength >= contentPreviewLength) {
                    assert subscription != null;
                    subscription.cancel();
                }
            }
            return obj;
        }
    }
}
