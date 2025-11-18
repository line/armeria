/*
 * Copyright 2024 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;

import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.common.EventLoopGroupExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;
import io.netty.util.concurrent.EventExecutor;

class ContextCancellationTest {

    private static final Set<String> requests = Sets.newConcurrentHashSet();
    private static final BlockingQueue<Thread> callbackThreads = new LinkedBlockingQueue<>();
    private static final String eventLoopThreadPrefix = "context-cancellation-test";
    private static final String HEADER = "x-request-id";

    @RegisterExtension
    static EventLoopGroupExtension eventLoopGroup = new EventLoopGroupExtension(4, eventLoopThreadPrefix);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                requests.add(req.headers().get(HEADER));
                return HttpResponse.streaming();
            });
        }
    };

    @BeforeEach
    void beforeEach() {
        requests.clear();
        callbackThreads.clear();
    }

    @Test
    void cancel_beforeDelegate(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener();
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        try (ClientFactory cf = ClientFactory
                .builder()
                .connectionPoolListener(connListener)
                .workerGroup(eventLoopGroup.get(), false)
                .build()) {
            final HttpResponse res = server.webClient(cb -> {
                cb.decorator((delegate, ctx, req) -> {
                    ctx.cancel(t);
                    ctxRef.set(ctx);
                    return delegate.execute(ctx, req);
                });
                cb.decorator(TestInfoHeaderDecorator.newDecorator(testInfo));
                cb.decorator(AttachCallbacksDecorator.newDecorator());
                cb.factory(cf);
            }).get("/");
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCause(t);
            assertThat(connListener.opened()).isEqualTo(0);
            assertThat(requests).doesNotContain(testInfo.getDisplayName());
        }
    }

    @Test
    void cancel_beforeConnection(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener();
        try (ClientFactory cf = ClientFactory
                .builder()
                .workerGroup(eventLoopGroup.get(), false)
                .addressResolverGroupFactory(
                        eventLoop -> MockAddressResolverGroup.of(ignored -> {
                            ctxRef.get().cancel(t);
                            try {
                                return InetAddress.getByName("127.0.0.1");
                            } catch (UnknownHostException e) {
                                throw new RuntimeException(e);
                            }
                        }))
                .connectionPoolListener(connListener).build()) {
            final HttpResponse res = WebClient.builder("http://foo.com:" + server.httpPort())
                                              .decorator((delegate, ctx, req) -> {
                                                  ctxRef.set(ctx);
                                                  return delegate.execute(ctx, req);
                                              })
                                              .decorator(TestInfoHeaderDecorator.newDecorator(testInfo))
                                              .decorator(AttachCallbacksDecorator.newDecorator())
                                              .factory(cf)
                                              .build()
                                              .execute(HttpRequest.streaming(HttpMethod.POST, "/"));
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCause(t);
            assertThat(requests).doesNotContain(testInfo.getDisplayName());
        }
    }

    @Test
    void cancel_afterConnection(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs)
                    throws Exception {
                super.connectionOpen(protocol, remoteAddr, localAddr, attrs);
                ctxRef.get().cancel(t);
            }
        };
        try (ClientFactory cf = ClientFactory
                .builder()
                .workerGroup(eventLoopGroup.get(), false)
                .connectionPoolListener(connListener)
                .build()) {
            final HttpResponse res = server.webClient(cb -> {
                cb.decorator((delegate, ctx, req) -> {
                    ctxRef.set(ctx);
                    return delegate.execute(ctx, req);
                });
                cb.decorator(TestInfoHeaderDecorator.newDecorator(testInfo));
                cb.decorator(AttachCallbacksDecorator.newDecorator());
                cb.factory(cf);
            }).execute(HttpRequest.streaming(HttpMethod.POST, "/"));
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCause(t);
            assertThat(connListener.opened()).isEqualTo(1);
            assertThat(requests).doesNotContain(testInfo.getDisplayName());
            validateCallbackChecks(eventLoopThreadPrefix);
        }
    }

    @Test
    void cancel_beforeSubscribe(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener();
        try (ClientFactory cf = ClientFactory
                .builder()
                .workerGroup(eventLoopGroup.get(), false)
                .connectionPoolListener(connListener)
                .build()) {
            final HttpResponse res = server.webClient(cb -> {
                cb.decorator((delegate, ctx, req) -> {
                    ctxRef.set(ctx);
                    return delegate.execute(ctx, req);
                });
                cb.decorator(TestInfoHeaderDecorator.newDecorator(testInfo));
                cb.decorator(AttachCallbacksDecorator.newDecorator());
                cb.factory(cf);
            }).execute(new DelegatingHttpRequest(HttpRequest.streaming(HttpMethod.POST, "/")) {
                @Override
                public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                                      SubscriptionOption... options) {
                    ctxRef.get().cancel(t);
                }
            });
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCause(t);
            assertThat(connListener.opened()).isEqualTo(1);
            assertThat(requests).doesNotContain(testInfo.getDisplayName());
            validateCallbackChecks(eventLoopThreadPrefix);
        }
    }

    @Test
    void cancel_beforeWriteFinished(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener();
        try (ClientFactory cf = ClientFactory
                .builder()
                .workerGroup(eventLoopGroup.get(), false)
                .connectionPoolListener(connListener)
                .build()) {
            final HttpResponse res = server.webClient(cb -> {
                cb.decorator((delegate, ctx, req) -> {
                    ctxRef.set(ctx);
                    return delegate.execute(ctx, req);
                });
                cb.decorator(TestInfoHeaderDecorator.newDecorator(testInfo));
                cb.decorator(AttachCallbacksDecorator.newDecorator());
                cb.factory(cf);
            }).execute(new DelegatingHttpRequest(HttpRequest.streaming(HttpMethod.POST, "/")) {
                @Override
                public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                                      SubscriptionOption... options) {
                    ctxRef.get().cancel(t);
                    super.subscribe(subscriber, executor, options);
                }
            });
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCause(t);
            assertThat(connListener.opened()).isEqualTo(1);
            validateCallbackChecks(eventLoopThreadPrefix);
        }
    }

    @Test
    void cancel_waitingForResponse(TestInfo testInfo) {
        final Throwable t = new Throwable();
        final CountingConnectionPoolListener connListener = new CountingConnectionPoolListener();
        try (ClientFactory cf = ClientFactory
                .builder()
                .workerGroup(eventLoopGroup.get(), false)
                .connectionPoolListener(connListener)
                .build();
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpResponse res = server.webClient(cb -> {
                cb.factory(cf);
                cb.decorator(TestInfoHeaderDecorator.newDecorator(testInfo));
                cb.decorator(AttachCallbacksDecorator.newDecorator());
            }).get("/");
            await().untilAsserted(() -> assertThat(requests).contains(testInfo.getDisplayName()));
            captor.get().cancel(t);
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(t);
            assertThat(connListener.opened()).isEqualTo(1);
            validateCallbackChecks(eventLoopThreadPrefix);
        }
    }

    static void validateCallbackChecks(@Nullable String expectedPrefix) {
        if (expectedPrefix != null) {
            assertThat(callbackThreads).allSatisfy(t -> assertThat(t.getName()).startsWith(expectedPrefix));
        }
    }

    private static class TestInfoHeaderDecorator extends SimpleDecoratingHttpClient {

        private final TestInfo testInfo;

        static Function<HttpClient, HttpClient> newDecorator(TestInfo testInfo) {
            return delegate -> new TestInfoHeaderDecorator(delegate, testInfo);
        }

        /**
         * Creates a new instance that decorates the specified {@link HttpClient}.
         */
        protected TestInfoHeaderDecorator(HttpClient delegate, TestInfo testInfo) {
            super(delegate);
            this.testInfo = testInfo;
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            final RequestHeaders requestHeaders = req.headers().toBuilder()
                                                     .add(HEADER, testInfo.getDisplayName())
                                                     .build();
            req = req.withHeaders(requestHeaders);
            ctx.updateRequest(req);
            return unwrap().execute(ctx, req);
        }
    }

    private static final class AttachCallbacksDecorator extends SimpleDecoratingHttpClient {

        static Function<HttpClient, HttpClient> newDecorator() {
            return AttachCallbacksDecorator::new;
        }

        private AttachCallbacksDecorator(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            attachCallbackChecks(ctx.log());
            return unwrap().execute(ctx, req);
        }

        private static void attachCallbackChecks(RequestLogAccess log) {
            final Runnable runnable = () -> {
                callbackThreads.add(Thread.currentThread());
            };
            log.whenRequestComplete().thenRun(runnable);
            log.whenComplete().thenRun(runnable);
        }
    }
}
