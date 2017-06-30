/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
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

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy) {
        return new RetryingHttpClientBuilder(retryStrategy).newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries HTTP requests.
     */
    public static Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient>
    newDecorator(RetryStrategy<HttpRequest, HttpResponse> retryStrategy,
                 Supplier<? extends Backoff> backoffSupplier) {
        return new RetryingHttpClientBuilder(retryStrategy)
                .backoffSupplier(backoffSupplier).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                       RetryStrategy<HttpRequest, HttpResponse> strategy,
                       Supplier<? extends Backoff> backoffSupplier,
                       int defaultMaxAttempts, boolean useRetryAfter) {
        super(delegate, strategy, backoffSupplier, defaultMaxAttempts);
        this.useRetryAfter = useRetryAfter;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        final Backoff backoff = newBackoff();
        final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(req);
        retry(1, backoff, ctx, reqDuplicator, newReq -> {
            try {
                return delegate().execute(ctx, newReq);
            } catch (Exception e) {
                return HttpResponse.ofFailure(e);
            }
        }, res);
        return res;
    }

    private void retry(int currentAttemptNo, Backoff backoff, ClientRequestContext ctx,
                       HttpRequestDuplicator rootReqDuplicator, Function<HttpRequest, HttpResponse> action,
                       DefaultHttpResponse rootResponse) {
        final HttpResponse response = action.apply(rootReqDuplicator.duplicateStream());
        final HttpResponseDuplicator resDuplicator = new HttpResponseDuplicator(response);
        retryStrategy().shouldRetry(rootReqDuplicator.duplicateStream(), resDuplicator.duplicateStream())
                       .handle(voidFunction((retry, unused) -> {
                           if (retry != null && retry) {
                               long millisAfter = useRetryAfter ? getRetryAfterMillis(
                                       resDuplicator.duplicateStream()) : -1;
                               resDuplicator.close();
                               retry0(currentAttemptNo, backoff, ctx, rootReqDuplicator, action,
                                      rootResponse, RetryGiveUpException.get(), millisAfter);
                           } else {
                               resDuplicator.duplicateStream().aggregate().handle(
                                       voidFunction((aRes, cause) -> {
                                           resDuplicator.close();
                                           final Throwable exception;
                                           if (cause != null) {
                                               if (!retryStrategy().shouldRetry(
                                                       rootReqDuplicator.duplicateStream(), cause)) {
                                                   rootReqDuplicator.close();
                                                   rootResponse.close(cause);
                                                   return;
                                               }
                                               exception = cause;
                                           } else {
                                               rootReqDuplicator.close();
                                               rootResponse.respond(aRes);
                                               return;
                                           }
                                           retry0(currentAttemptNo, backoff, ctx, rootReqDuplicator, action,
                                                  rootResponse, exception, -1);
                                       }));
                           }
                       }));
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
                logger.debug("The retryAfter: {}, from the server is neither a http date nor a second.", value);
            }
        }
        return millisAfter;
    }

    private static HttpHeaders getHttpHeaders(HttpResponse res) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpHeaderSubscriber subscriber = new HttpHeaderSubscriber(future);
        res.closeFuture().whenComplete(subscriber);
        res.subscribe(subscriber);
        // Neither blocks here nor throws an exception because it already has headers.
        return future.join().headers();
    }

    private void retry0(int currentAttemptNo, Backoff backoff, ClientRequestContext ctx,
                        HttpRequestDuplicator rootReqDuplicator, Function<HttpRequest, HttpResponse> action,
                        DefaultHttpResponse rootResponse, Throwable exception, long millisAfter) {
        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            rootResponse.close(exception);
        } else {
            final EventLoop eventLoop = ctx.contextAwareEventLoop();
            nextDelay = Math.min(Math.max(nextDelay, millisAfter), ctx.responseTimeoutMillis());
            if (nextDelay <= 0) {
                eventLoop.submit(() -> retry(currentAttemptNo + 1, backoff, ctx,
                                             rootReqDuplicator, action,
                                             rootResponse));
            } else {
                eventLoop.schedule(
                        () -> retry(currentAttemptNo + 1, backoff, ctx,
                                    rootReqDuplicator, action, rootResponse),
                        nextDelay, TimeUnit.MILLISECONDS);
            }
        }
    }
}
