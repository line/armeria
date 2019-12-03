/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.channel.EventLoop;

class ClientRequestContextPushedOnSubscriberTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, Armeria!"));
            sb.service("/noResponse", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @ParameterizedTest
    @ArgumentsSource(ClientDecoratorProvider.class)
    void contextPushedOnComplete(DecoratingHttpClientFunction decorator) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final WebClient client = WebClient.builder(server.uri("/"))
                                          .decorator(decorator)
                                          .decorator((delegate, ctx, req) -> {
                                              ctxHolder.set(ctx);
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();
        final ArrayList<RequestContext> requestContexts = new ArrayList<>();
        client.get("/").subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                requestContexts.add(RequestContext.current());
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                requestContexts.add(RequestContext.current());
                latch.countDown();
            }
        }, nextEventLoop());
        latch.await();
        assertThat(requestContexts.size()).isEqualTo(2);
        final ClientRequestContext ctx = ctxHolder.get();
        assertThat(requestContexts).containsExactly(ctx, ctx);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientDecoratorProvider.class)
    void contextPushedOnError(DecoratingHttpClientFunction decorator) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final WebClient client = WebClient.builder(server.uri("/"))
                                          .responseTimeoutMillis(500)
                                          .decorator(decorator)
                                          .decorator((delegate, ctx, req) -> {
                                              ctxHolder.set(ctx);
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();
        final ArrayList<RequestContext> requestContexts = new ArrayList<>();
        client.get("/noResponse").subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                requestContexts.add(RequestContext.current());
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {
                requestContexts.add(RequestContext.current());
                latch.countDown();
            }

            @Override
            public void onComplete() {}
        }, nextEventLoop());

        latch.await();
        assertThat(requestContexts.size()).isEqualTo(2);
        final ClientRequestContext ctx = ctxHolder.get();
        assertThat(requestContexts).containsExactly(ctx, ctx);
    }

    @Test
    void contextPushedOnErrorWhenAbortingResponse() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final WebClient client = WebClient.builder(server.uri("/"))
                                          .decorator((delegate, ctx, req) -> {
                                              ctxHolder.set(ctx);
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();
        final ArrayList<RequestContext> requestContexts = new ArrayList<>();
        final HttpResponse res = client.get("/noResponse");
        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                requestContexts.add(RequestContext.current());
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {
                requestContexts.add(RequestContext.current());
                latch.countDown();
            }

            @Override
            public void onComplete() {}
        }, nextEventLoop());
        res.abort();

        latch.await();
        assertThat(requestContexts.size()).isEqualTo(2);
        final ClientRequestContext ctx = ctxHolder.get();
        assertThat(requestContexts).containsExactly(ctx, ctx);
    }

    @Test
    void contextPushedOnErrorWhenSubscribingAbortedResponse() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final WebClient client = WebClient.builder(server.uri("/"))
                                          .decorator((delegate, ctx, req) -> {
                                              ctxHolder.set(ctx);
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();
        final ArrayList<RequestContext> requestContexts = new ArrayList<>();
        final HttpResponse res = client.get("/noResponse");
        res.abort();

        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                requestContexts.add(RequestContext.current());
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {
                requestContexts.add(RequestContext.current());
                latch.countDown();
            }

            @Override
            public void onComplete() {}
        }, nextEventLoop());

        latch.await();
        assertThat(requestContexts.size()).isEqualTo(2);
        final ClientRequestContext ctx = ctxHolder.get();
        assertThat(requestContexts).containsExactly(ctx, ctx);
    }

    private static EventLoop nextEventLoop() {
        return CommonPools.workerGroup().next();
    }

    private static class ClientDecoratorProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final DecoratingHttpClientFunction decodedHttpResponse = new DecoratingHttpClientFunction() {
                @Override
                public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return delegate.execute(ctx, req);
                }

                @Override
                public String toString() {
                    return "DecodedHttpResponse";
                }
            };
            final DecoratingHttpClientFunction deferredHttpResponse = new DecoratingHttpClientFunction() {
                @Override
                public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
                        throws Exception {
                    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                    nextEventLoop().submit(() -> future.complete(delegate.execute(ctx, req)));
                    return HttpResponse.from(future);
                }

                @Override
                public String toString() {
                    return "DeferredHttpResponse";
                }
            };
            final DecoratingHttpClientFunction duplicatedResponse = new DecoratingHttpClientFunction() {
                @Override
                public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
                        throws Exception {
                    final HttpResponse res = delegate.execute(ctx, req);
                    final HttpResponseDuplicator duplicator =
                            new HttpResponseDuplicator(res, 0, nextEventLoop());
                    return duplicator.duplicateStream(true);
                }

                @Override
                public String toString() {
                    return "DuplicatedResponse";
                }
            };

            return Stream.of(decodedHttpResponse,
                             deferredHttpResponse,
                             duplicatedResponse).map(Arguments::of);
        }
    }
}
