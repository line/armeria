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

package com.linecorp.armeria.client;

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

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.client.RequestCancellation;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

class RedirectingClientCancellationTest {

    @Test
    void cancellationBeforeAggregatePublicationReleasesLatePooledContent() throws Exception {
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
        final RedirectingClient client = newRedirectingClient((unused, req) -> {
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
    void cancellationWhileDrainingRedirectStopsNextHopAndReleasesRequest() throws Exception {
        final RequestCancellation cancellation = new RequestCancellation();
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.wrap(content));
        final RequestOptionsBuilder options = RequestOptions.builder().exchangeType(ExchangeType.UNARY);
        cancellation.attach(options);
        final ClientRequestContext ctx = ClientRequestContext.builder(request)
                                                            .requestOptions(options.build())
                                                            .build();
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                           HttpHeaderNames.LOCATION, "/next");
        final HttpResponseWriter redirect = HttpResponse.streaming();
        redirect.write(headers);
        final AtomicInteger attempts = new AtomicInteger();
        final RedirectingClient client = newRedirectingClient((derivedCtx, req) -> {
            attempts.incrementAndGet();
            derivedCtx.logBuilder().requestHeaders(req.headers());
            derivedCtx.logBuilder().responseHeaders(headers);
            return redirect;
        });
        final CompletableFuture<AggregatedHttpResponse> response = client.execute(ctx, request).aggregate();
        try {
            redirect.whenConsumed().get(5, TimeUnit.SECONDS);
            assertThat(content.refCnt()).isPositive();
            cancellation.cancel(new AnticipatedException());
            assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AnticipatedException.class);
            assertThatThrownBy(() -> redirect.whenComplete().get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AnticipatedException.class);
            ctx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
            await().untilAsserted(() -> assertThat(content.refCnt()).isZero());
            assertThat(attempts).hasValue(1);
        } finally {
            cancellation.cancel(new AnticipatedException());
            redirect.abort();
        }
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
                                          .followRedirects()
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
            await().untilAsserted(() -> contents.forEach(buffer -> assertThat(buffer.refCnt()).isZero()));
            assertThat(factoryCalls).hasValue(1);
            assertThat(attempts).hasValue(0);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static RedirectingClient newRedirectingClient(HttpClient delegate) {
        return new RedirectingClient(delegate, ImmutableSet.of(SessionProtocol.HTTP, SessionProtocol.HTTPS),
                                     (ctx, domain) -> true, 16);
    }
}
