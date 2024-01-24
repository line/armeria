/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

class ServiceWorkerGroupTest {

    static final EventLoop aExecutor =
            new DefaultEventLoop(ThreadFactories.builder("test-a")
                                                    .eventLoop(false)
                                                    .build());

    static final EventLoop defaultExecutor = new DefaultEventLoop(
            ThreadFactories.builder("test-default")
                           .eventLoop(false)
                           .build());

    static final EventLoop workerExecutor = new DefaultEventLoop(
            ThreadFactories.builder("test-worker")
                           .eventLoop(false)
                           .build());

    static final Queue<Thread> threadQueue = new ArrayBlockingQueue<>(32);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService().serviceWorkerGroup(aExecutor, true)
              .build(new MyAnnotatedServiceA());
            sb.annotatedService(new MyAnnotatedServiceDefault());
            sb.service("/ctxLog", (ctx, req) -> {
                for (RequestLogProperty property: RequestLogProperty.values()) {
                    ctx.log().whenAvailable(property).thenRun(() -> {
                        threadQueue.add(Thread.currentThread());
                    });
                }
                return HttpResponse.of(200);
            });
            sb.service("/subscribe", (ctx, req) -> {
                final SignallingPublisher publisher = new SignallingPublisher();
                req.subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        threadQueue.add(Thread.currentThread());
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {
                        threadQueue.add(Thread.currentThread());
                    }

                    @Override
                    public void onError(Throwable t) {
                        threadQueue.add(Thread.currentThread());
                    }

                    @Override
                    public void onComplete() {
                        threadQueue.add(Thread.currentThread());
                        publisher.markReady();
                    }
                });
                return HttpResponse.of(publisher);
            });

            sb.serviceWorkerGroup(defaultExecutor, true);
        }
    };

    static class SignallingPublisher implements Publisher<HttpObject> {

        private boolean ready;
        @Nullable
        private Subscriber<? super HttpObject> subscriber;
        private long request;

        @Override
        public void subscribe(Subscriber<? super HttpObject> s) {
            this.subscriber = s;
            threadQueue.add(Thread.currentThread());
            s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    threadQueue.add(Thread.currentThread());
                    request += n;
                    tryNotify();
                }

                @Override
                public void cancel() {
                }
            });
        }

        void markReady() {
            ready = true;
            tryNotify();
        }

        private void tryNotify() {
            final Subscriber<? super HttpObject> subscriber0 = subscriber;
            if (request > 0 && ready && subscriber0 != null) {
                subscriber0.onNext(ResponseHeaders.builder(200).endOfStream(true).build());
                subscriber0.onComplete();
            }
        }
    }

    @BeforeEach
    void beforeEach() {
        threadQueue.clear();
    }

    @AfterAll
    static void afterAll() {
        aExecutor.shutdownGracefully();
        defaultExecutor.shutdownGracefully();
        workerExecutor.shutdownGracefully();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a", "/aggregated"})
    void testServiceWorkerGroup(String path) throws InterruptedException {
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .execute(RequestHeaders.of(HttpMethod.GET, path));
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final EventLoop ctxEventLoop = server.requestContextCaptor().poll().eventLoop().withoutContext();
        assertThat(ctxEventLoop).isSameAs(aExecutor);
        assertThat(threadQueue).allSatisfy(t -> assertThat(aExecutor.inEventLoop(t)).isTrue());
    }

    @Test
    void aggregatingRequest() throws InterruptedException {
        final AggregatedHttpResponse res = server.blockingWebClient().post("/aggregated-string", "hello");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final EventLoop ctxEventLoop = server.requestContextCaptor().poll().eventLoop().withoutContext();
        assertThat(ctxEventLoop).isSameAs(aExecutor);
        assertThat(threadQueue).allSatisfy(t -> assertThat(aExecutor.inEventLoop(t)).isTrue());
    }

    @Test
    void testDefaultServiceWorkerGroup() throws InterruptedException {
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .execute(RequestHeaders.of(HttpMethod.GET, "/default"));
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final EventLoop ctxEventLoop = server.requestContextCaptor().poll().eventLoop().withoutContext();
        assertThat(ctxEventLoop).isSameAs(defaultExecutor);
    }

    @Test
    void shutdownOnStopBehavior() {
        final EventLoop eventLoop = new DefaultEventLoop();
        try (Server server = Server.builder()
                                   .service("/", (ctx, req) -> HttpResponse.of(200))
                                   .serviceWorkerGroup(eventLoop, true)
                                   .build()) {
            server.start().join();
            assertThat(eventLoop.isShutdown()).isFalse();
        }
        assertThat(eventLoop.isShutdown()).isTrue();
    }

    @Test
    void defaultIsWorkerThread() {
        try (Server server = Server.builder()
                                   .service("/", (ctx, req) -> HttpResponse.of(200))
                                   .workerGroup(workerExecutor, false)
                                   .build()) {
            assertThat(server.serviceConfigs()).allSatisfy(cfg -> {
                assertThat(cfg.serviceWorkerGroup()).isSameAs(workerExecutor);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ctxLog", "/aggregatedCtxLog"})
    void contextLogExecutedByServiceWorkerThread(String path) {
        final AggregatedHttpResponse aggRes = server.blockingWebClient().get(path);
        assertThat(aggRes.status().code()).isEqualTo(200);

        await().untilAsserted(() -> assertThat(threadQueue).hasSize(RequestLogProperty.values().length));
        assertThat(threadQueue).allSatisfy(t -> assertThat(defaultExecutor.inEventLoop(t)).isTrue());
    }

    @Test
    void subscribeWorkerThread() {
        final AggregatedHttpResponse aggRes = server.blockingWebClient().get("/subscribe");
        assertThat(aggRes.status().code()).isEqualTo(200);

        await().untilAsserted(() -> assertThat(threadQueue).isNotEmpty());
        assertThat(threadQueue).allSatisfy(t -> assertThat(defaultExecutor.inEventLoop(t)).isTrue());
    }

    static class MyAnnotatedServiceA {
        @Get("/a")
        public HttpResponse httpResponseLoggingServiceTest() {
            threadQueue.add(Thread.currentThread());
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/aggregated")
        public String aggregated() {
            threadQueue.add(Thread.currentThread());
            return "aggregated";
        }

        @Post("/aggregated-string")
        public String aggregated(String request) {
            threadQueue.add(Thread.currentThread());
            return request + ", World!";
        }
    }

    static class MyAnnotatedServiceDefault {
        @Get("/default")
        public HttpResponse httpResponse(ServiceRequestContext ctx) {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/aggregatedCtxLog")
        public String aggregated(ServiceRequestContext ctx) {
            for (RequestLogProperty property: RequestLogProperty.values()) {
                ctx.log().whenAvailable(property).thenRun(() -> {
                    threadQueue.add(Thread.currentThread());
                });
            }
            return "aggregated";
        }
    }
}
