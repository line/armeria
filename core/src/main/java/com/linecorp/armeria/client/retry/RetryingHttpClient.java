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
import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.DeferredHttpResponse;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.internal.HttpHeaderSubscriber;

import io.netty.channel.EventLoop;

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
     * @param defaultMaxAttempts the default number of max attempts for retry
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy, int defaultMaxAttempts) {
        return new RetryingHttpClientBuilder(retryStrategy).defaultMaxAttempts(defaultMaxAttempts)
                                                           .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     *
     * @param retryStrategy the retry strategy
     * @param defaultMaxAttempts the default number of max attempts for retry
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy,
                 int defaultMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        return new RetryingHttpClientBuilder(retryStrategy)
                .defaultMaxAttempts(defaultMaxAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                       RetryStrategy<HttpRequest, HttpResponse> strategy, int defaultMaxAttempts,
                       long responseTimeoutMillisForEachAttempt, boolean useRetryAfter,
                       int contentPreviewLength) {
        super(delegate, strategy, defaultMaxAttempts, responseTimeoutMillisForEachAttempt);
        this.useRetryAfter = useRetryAfter;
        checkArgument(contentPreviewLength >= 0,
                      "contentPreviewLength: %s (expected: >= 0)", contentPreviewLength);
        this.contentPreviewLength = contentPreviewLength;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final DeferredHttpResponse deferredRes = new DeferredHttpResponse();
        final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(req, 0);
        doExecute0(ctx, reqDuplicator, deferredRes);
        return deferredRes;
    }

    private void doExecute0(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                            DeferredHttpResponse deferredRes) {
        if (!setResponseTimeout(ctx)) {
            closeOnException(deferredRes, rootReqDuplicator, ResponseTimeoutException.get());
            return;
        }
        HttpResponse response = executeDelegate(ctx, rootReqDuplicator.duplicateStream());

        final HttpResponseDuplicator resDuplicator =
                new HttpResponseDuplicator(response, maxSignalLength(ctx.maxResponseLength()));
        final ContentPreviewResponse contentPreviewResponse =
                new ContentPreviewResponse(resDuplicator.duplicateStream(), contentPreviewLength);
        retryStrategy().shouldRetry(rootReqDuplicator.duplicateStream(), contentPreviewResponse)
                       .handle(voidFunction((backoffOpt, unused) -> {
                           if (backoffOpt.isPresent()) {
                               final long millisAfter =
                                       useRetryAfter ? getRetryAfterMillis(resDuplicator.duplicateStream())
                                                     : -1;
                               resDuplicator.close();

                               long nextDelay;
                               try {
                                   nextDelay = getNextDelay(ctx, backoffOpt.get(), millisAfter);
                               } catch (Exception e) {
                                   closeOnException(deferredRes, rootReqDuplicator, e);
                                   return;
                               }

                               final EventLoop eventLoop = ctx.contextAwareEventLoop();
                               if (nextDelay <= 0) {
                                   eventLoop.execute(() -> doExecute0(ctx, rootReqDuplicator, deferredRes));
                               } else {
                                   eventLoop.schedule(
                                           () -> doExecute0(ctx, rootReqDuplicator, deferredRes),
                                           nextDelay, TimeUnit.MILLISECONDS);
                               }
                           } else {
                               deferredRes.delegate(resDuplicator.duplicateStream(true));
                               rootReqDuplicator.close();
                           }
                       }));
    }

    private HttpResponse executeDelegate(ClientRequestContext ctx, HttpRequest req) {
        try {
            return delegate().execute(ctx, req);
        } catch (Exception e) {
            return HttpResponse.ofFailure(e);
        }
    }

    private static int maxSignalLength(long maxResponseLength) {
        if (maxResponseLength == 0 || maxResponseLength > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxResponseLength;
    }

    private static long getRetryAfterMillis(HttpResponse res) {
        final HttpHeaders headers = getHttpHeaders(res);
        long millisAfter = -1;
        String value = headers.get(HttpHeaderNames.RETRY_AFTER);
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
        // Neither blocks here nor throws an exception because it already has headers.
        return future.join();
    }

    private static void closeOnException(DeferredHttpResponse deferredRes,
                                         HttpRequestDuplicator rootReqDuplicator, Throwable cause) {
        deferredRes.close(cause);
        rootReqDuplicator.close();
    }

    private static class ContentPreviewResponse extends FilteredHttpResponse {

        private final int contentPreviewLength;
        private int contentLength;
        private Subscription subscription;

        ContentPreviewResponse(HttpResponse delegate, int contentPreviewLength) {
            super(delegate);
            if (contentPreviewLength == 0) {
                this.contentPreviewLength = Integer.MAX_VALUE;
            } else {
                this.contentPreviewLength = contentPreviewLength;
            }
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
                    subscription.cancel();
                }
            }
            return obj;
        }
    }
}
