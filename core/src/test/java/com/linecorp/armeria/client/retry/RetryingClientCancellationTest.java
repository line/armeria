/*
 * Copyright 2026 LY Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.client.RequestCancellation;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetryingClientCancellationTest {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelsActualAttemptIncludingNestedRetries(boolean nested) throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final HttpResponse attemptResponse = HttpResponse.streaming();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final RetryRule rule = RetryRule.onException();
        final WebClientBuilder builder =
                WebClient.builder("http://127.0.0.1:1")
                         .responseTimeoutMillis(0)
                         .decorator((delegate, ctx, req) -> {
                             started.complete(null);
                             return attemptResponse;
                         })
                         .decorator(RetryingClient.newDecorator(rule));
        if (nested) {
            builder.decorator(RetryingClient.newDecorator(rule));
        }
        final CompletableFuture<AggregatedHttpResponse> response =
                execute(builder.build(), cancellation).aggregate();
        started.get(5, TimeUnit.SECONDS);
        cancellation.cancel(new AnticipatedException());
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        assertThatThrownBy(() -> attemptResponse.whenComplete().get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
    }

    @Test
    void cancellationDoesNotWaitForRetryRuleOrAdmitItsLateRetry() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CompletableFuture<RetryDecision> decision = new CompletableFuture<>();
        final CompletableFuture<ClientRequestContext> judging = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        final RetryRule rule = (ctx, cause) -> {
            judging.complete(ctx);
            return decision;
        };
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> {
                                              attempts.incrementAndGet();
                                              return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                                          })
                                          .decorator(RetryingClient.newDecorator(rule))
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> response = execute(client, cancellation).aggregate();
        final ClientRequestContext attemptCtx = judging.get(5, TimeUnit.SECONDS);
        cancellation.cancel(new AnticipatedException());
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        assertThat(decision).isNotDone();
        decision.complete(RetryDecision.retry(Backoff.withoutDelay()));
        attemptCtx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void cancellationReleasesPooledContentWhileContentRuleIsStillPending() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CompletableFuture<RetryDecision> decision = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> judging = new CompletableFuture<>();
        final CompletableFuture<AggregatedHttpResponse> consumed = new CompletableFuture<>();
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        final HttpResponse attemptResponse = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                                             HttpData.wrap(content));
        final RetryRuleWithContent<HttpResponse> rule = (ctx, response, cause) -> {
            judging.complete(response);
            response.aggregate().handle((aggregated, aggregateCause) -> {
                if (aggregateCause != null) {
                    consumed.completeExceptionally(aggregateCause);
                } else {
                    consumed.complete(aggregated);
                }
                return null;
            });
            return decision;
        };
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> attemptResponse)
                                          .decorator(RetryingClient.newDecorator(rule))
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> response = execute(client, cancellation).aggregate();
        final HttpResponse ruleResponse = judging.get(5, TimeUnit.SECONDS);
        assertThat(consumed.get(5, TimeUnit.SECONDS).content().length()).isEqualTo(1);
        attemptResponse.whenComplete().get(5, TimeUnit.SECONDS);
        assertThat(content.refCnt()).isPositive();
        cancellation.cancel(new AnticipatedException());
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        ruleResponse.whenComplete().get(5, TimeUnit.SECONDS);
        await().untilAsserted(() -> assertThat(content.refCnt()).isZero());
        assertThat(decision).isNotDone();
        decision.complete(RetryDecision.noRetry());
    }

    @Test
    void cancellationDoesNotWaitForReproducibleBodyFactory() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CountDownLatch producing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger factoryCalls = new AtomicInteger();
        final ConcurrentLinkedQueue<ByteBuf> contents = new ConcurrentLinkedQueue<>();
        final CompletableFuture<StreamMessage<HttpData>> firstBody = new CompletableFuture<>();
        final HttpRequest request = HttpRequest.reproducible(RequestHeaders.of(HttpMethod.POST, "/"), () -> {
            factoryCalls.incrementAndGet();
            producing.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
            contents.add(content);
            final StreamMessage<HttpData> body = StreamMessage.of(HttpData.wrap(content));
            firstBody.complete(body);
            return body;
        });
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> {
                                              attempts.incrementAndGet();
                                              return HttpResponse.of(HttpStatus.OK);
                                          })
                                          .decorator(RetryingClient.newDecorator(RetryRule.onException()))
                                          .build();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<HttpResponse> result = executor.submit(() -> {
                final RequestOptionsBuilder options =
                        RequestOptions.builder().exchangeType(ExchangeType.REQUEST_STREAMING);
                cancellation.attach(options);
                return client.execute(request, options.build());
            });
            assertThat(producing.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel(new AnticipatedException());
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown();
            final HttpResponse response = result.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> response.aggregate().get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AnticipatedException.class);
            final StreamMessage<HttpData> body = firstBody.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> body.whenComplete().get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AnticipatedException.class);
            await().untilAsserted(() -> contents.forEach(content -> assertThat(content.refCnt()).isZero()));
            assertThat(factoryCalls).hasValue(1);
            assertThat(attempts).hasValue(0);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = { 0, 60000 })
    void cancelsQueuedRetryAndPreservesCause(long delayMillis) throws Exception {
        final RecordingEventLoop eventLoop = new RecordingEventLoop();
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final RequestCancellation cancellation = new RequestCancellation();
            final RequestOptionsBuilder options = RequestOptions.builder();
            cancellation.attach(options);
            final ClientRequestContext ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                .eventLoop(eventLoop)
                                                                .requestOptions(options.build())
                                                                .build();
            final CompletableFuture<Void> blocked = new CompletableFuture<>();
            eventLoop.execute(() -> {
                blocked.complete(null);
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            blocked.get(5, TimeUnit.SECONDS);
            eventLoop.recording = true;
            final CompletableFuture<Throwable> failed = new CompletableFuture<>();
            final AtomicInteger attempts = new AtomicInteger();
            AbstractRetryingClient.scheduleNextRetry(ctx, failed::complete, attempts::incrementAndGet,
                                                    delayMillis);
            final ScheduledFuture<?> timer = eventLoop.scheduled.get(5, TimeUnit.SECONDS);
            final Throwable cause = new AnticipatedException();
            cancellation.cancel(cause);
            assertThat(timer.isCancelled()).isTrue();
            release.countDown();
            assertThat(failed.get(5, TimeUnit.SECONDS)).isSameAs(cause);
            eventLoop.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(attempts).hasValue(0);
        } finally {
            release.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void cancellationOfDerivedContextPreventsDispatchAfterRetryLimiterReturns() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CountDownLatch checking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<ClientRequestContext> derived = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        final RetryLimiter limiter = ctx -> {
            derived.complete(ctx);
            checking.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return true;
        };
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> {
                                              attempts.incrementAndGet();
                                              return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                                          })
                                          .decorator(RetryingClient.newDecorator(RetryConfig.builder(
                                                  RetryRule.onServerErrorStatus(Backoff.withoutDelay()))
                                                                                          .retryLimiter(limiter)
                                                                                          .build()))
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> response = execute(client, cancellation).aggregate();
        try {
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel(new AnticipatedException());
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        final ClientRequestContext derivedCtx = derived.get(5, TimeUnit.SECONDS);
        derivedCtx.whenResponseCancelling().get(5, TimeUnit.SECONDS);
        derivedCtx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void cancellationReleasesIssuedRequestBeforeRequestIdGeneratorReturns() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CountDownLatch generating = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger idCalls = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .requestIdGenerator(() -> {
                                              if (idCalls.incrementAndGet() == 2) {
                                                  generating.countDown();
                                                  try {
                                                      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                                  } catch (InterruptedException e) {
                                                      throw new AssertionError(e);
                                                  }
                                              }
                                              return RequestId.random();
                                          })
                                          .decorator((delegate, ctx, req) -> {
                                              attempts.incrementAndGet();
                                              return HttpResponse.of(HttpStatus.OK);
                                          })
                                          .decorator(RetryingClient.newDecorator(RetryRule.onException()))
                                          .build();
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.wrap(content));
        final RequestOptionsBuilder options = RequestOptions.builder().exchangeType(ExchangeType.UNARY);
        cancellation.attach(options);
        final HttpResponse response = client.execute(request, options.build());
        final CompletableFuture<AggregatedHttpResponse> aggregated = response.aggregate();
        try {
            assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(content.refCnt()).isPositive();
            cancellation.cancel(new AnticipatedException());
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(content.refCnt()).isZero();
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> aggregated.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        assertThat(attempts).hasValue(0);
    }

    @Test
    void cancellationBeforeAggregatePublicationReleasesItsLatePooledContent() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final HttpRequest request = mock(HttpRequest.class, delegatesTo(HttpRequest.of(HttpMethod.POST, "/")));
        final CompletableFuture<AggregatedHttpRequest> aggregation = new CompletableFuture<>();
        doReturn(aggregation).when(request).aggregate(any(AggregationOptions.class));
        final RequestOptionsBuilder options = RequestOptions.builder().exchangeType(ExchangeType.UNARY);
        cancellation.attach(options);
        final ClientRequestContext ctx = ClientRequestContext.builder(request)
                                                            .requestOptions(options.build())
                                                            .build();
        final AtomicInteger attempts = new AtomicInteger();
        final RetryingClient client = RetryingClient.newDecorator(RetryRule.onException())
                                                    .apply((HttpClient) (unused, req) -> {
                                                        attempts.incrementAndGet();
                                                        return HttpResponse.of(HttpStatus.OK);
                                                    });
        final CompletableFuture<AggregatedHttpResponse> response = client.execute(ctx, request).aggregate();
        cancellation.cancel(new AnticipatedException());
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AnticipatedException.class);
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        aggregation.complete(AggregatedHttpRequest.of(request.headers(), HttpData.wrap(content)));
        assertThat(content.refCnt()).isZero();
        assertThat(attempts).hasValue(0);
    }

    @Test
    void cancellationRacingFinalDecisionDisposesUnclaimedResponse() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final CompletableFuture<RetryDecision> decision = new CompletableFuture<>();
        final CompletableFuture<Void> judging = new CompletableFuture<>();
        final CountDownLatch publishing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        final HttpResponse attempt = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.wrap(content));
        final RetryRule rule = (ctx, cause) -> {
            judging.complete(null);
            return decision;
        };
        final RetryLimiter limiter = new RetryLimiter() {
            @Override
            public boolean shouldRetry(ClientRequestContext ctx) {
                return true;
            }

            @Override
            public void handleDecision(ClientRequestContext ctx, RetryDecision unused) {
                publishing.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> attempt)
                                          .decorator(RetryingClient.newDecorator(RetryConfig.builder(
                                                  rule).retryLimiter(limiter).build()))
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> response = execute(client, cancellation).aggregate();
        judging.get(5, TimeUnit.SECONDS);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<?> published = executor.submit(() -> decision.complete(RetryDecision.noRetry()));
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel(new AnticipatedException());
            assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AnticipatedException.class);
            release.countDown();
            published.get(5, TimeUnit.SECONDS);
            await().untilAsserted(() -> assertThat(content.refCnt()).isZero());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static HttpResponse execute(WebClient client, RequestCancellation cancellation) {
        final RequestOptionsBuilder options = RequestOptions.builder()
                                                           .exchangeType(ExchangeType.RESPONSE_STREAMING);
        cancellation.attach(options);
        return client.execute(HttpRequest.of(HttpMethod.GET, "/"), options.build());
    }

    private static final class RecordingEventLoop extends DefaultEventLoop {

        final CompletableFuture<ScheduledFuture<?>> scheduled = new CompletableFuture<>();
        volatile boolean recording;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            final ScheduledFuture<?> future = super.schedule(task, delay, unit);
            if (recording) {
                scheduled.complete(future);
            }
            return future;
        }
    }
}
