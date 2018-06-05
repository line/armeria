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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
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
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.HttpHeaderSubscriber;

/**
 * A {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingHttpClient extends RetryingClient<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(RetryingHttpClient.class);

    private final boolean useRetryAfter;

    private final int contentPreviewLength;

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy) {
        return new RetryingHttpClientBuilder(retryStrategy).newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy, int maxTotalAttempts) {
        return new RetryingHttpClientBuilder(retryStrategy).maxTotalAttempts(maxTotalAttempts)
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
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return new RetryingHttpClientBuilder(retryStrategy)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                       RetryStrategy<HttpRequest, HttpResponse> strategy, int totalMaxAttempts,
                       long responseTimeoutMillisForEachAttempt, boolean useRetryAfter,
                       int contentPreviewLength) {
        super(delegate, strategy, totalMaxAttempts, responseTimeoutMillisForEachAttempt);
        this.useRetryAfter = useRetryAfter;
        checkArgument(contentPreviewLength >= 0,
                      "contentPreviewLength: %s (expected: >= 0)", contentPreviewLength);
        this.contentPreviewLength = contentPreviewLength;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture);
        final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(req, 0, ctx.eventLoop());
        doExecute0(ctx, reqDuplicator, req, res, responseFuture);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                            HttpRequest originalReq, HttpResponse returnedRes,
                            CompletableFuture<HttpResponse> future) {
        if (originalReq.completionFuture().isCompletedExceptionally() || returnedRes.isComplete()) {
            // The request or response has been aborted by the client before it receives a response,
            // so stop retrying.
            handleException(ctx, rootReqDuplicator, future, AbortedStreamException.get());
            return;
        }

        if (!setResponseTimeout(ctx)) {
            handleException(ctx, rootReqDuplicator, future, ResponseTimeoutException.get());
            return;
        }

        HttpResponse response;
        try {
            response = executeDelegate(ctx, rootReqDuplicator.duplicateStream());
        } catch (Exception e) {
            response = HttpResponse.ofFailure(e);
        }

        final HttpResponseDuplicator resDuplicator =
                new HttpResponseDuplicator(response, maxSignalLength(ctx.maxResponseLength()), ctx.eventLoop());
        retryStrategy().shouldRetry(rootReqDuplicator.duplicateStream(), contentPreviewResponse(resDuplicator))
                       .whenComplete((backoff, unused) -> {
                           if (backoff != null) {
                               final long millisAfter;
                               if (useRetryAfter) {
                                   millisAfter = getRetryAfterMillis(contentPreviewResponse(resDuplicator));
                               } else {
                                   millisAfter = -1;
                               }

                               final long nextDelay = getNextDelay(ctx, backoff, millisAfter);
                               if (nextDelay < 0) {
                                   finishRetryWithCurrentResponse(
                                           ctx, rootReqDuplicator, future, resDuplicator);
                                   return;
                               }

                               resDuplicator.close();
                               scheduleNextRetry(
                                       ctx, cause -> handleException(ctx, rootReqDuplicator, future, cause),
                                       () -> doExecute0(ctx, rootReqDuplicator, originalReq,
                                                        returnedRes, future),
                                       nextDelay);
                           } else {
                               finishRetryWithCurrentResponse(ctx, rootReqDuplicator, future, resDuplicator);
                           }
                       });
    }

    private static void handleException(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause) {
        onRetryingComplete(ctx);
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

    private static long getRetryAfterMillis(HttpResponse res) {
        final HttpHeaders headers = getHttpHeaders(res);
        long millisAfter = -1;
        final String value = headers.get(HttpHeaderNames.RETRY_AFTER);
        if (value != null) {
            try {
                millisAfter = Duration.ofSeconds(Integer.parseInt(value)).toMillis();
                return millisAfter;
            } catch (Exception ignored) {
                // Not a second value.
            }
            try {
                final Long later = headers.getTimeMillis(HttpHeaderNames.RETRY_AFTER);
                millisAfter = later - System.currentTimeMillis();
            } catch (Exception ignored) {
                logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                             value);
            }
        }
        return millisAfter;
    }

    private static HttpHeaders getHttpHeaders(HttpResponse res) {
        final CompletableFuture<HttpHeaders> future = new CompletableFuture<>();
        final HttpHeaderSubscriber subscriber = new HttpHeaderSubscriber(future);
        res.completionFuture().whenComplete(subscriber);
        res.subscribe(subscriber);
        // Future does not block here because it is already complete.
        return future.handle((headers, thrown) -> thrown != null ? HttpHeaders.EMPTY_HEADERS : headers).join();
    }

    private static void finishRetryWithCurrentResponse(ClientRequestContext ctx,
                                                       HttpRequestDuplicator rootReqDuplicator,
                                                       CompletableFuture<HttpResponse> res,
                                                       HttpResponseDuplicator resDuplicator) {
        onRetryingComplete(ctx);
        res.complete(resDuplicator.duplicateStream(true));
        rootReqDuplicator.close();
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
