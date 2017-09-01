/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.ProgressivePromise;
import io.netty.util.concurrent.Promise;

public class RequestContextTest {

    private static final EventLoop eventLoop = new DefaultEventLoop();

    @AfterClass
    public static void stopEventLoop() {
        eventLoop.shutdownGracefully();
    }

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Mock
    private Channel channel;

    private final List<RequestContext> ctxStack = new ArrayList<>();

    @Test
    public void contextAwareEventExecutor() throws Exception {
        when(channel.eventLoop()).thenReturn(eventLoop);
        RequestContext context = createContext();
        Set<Integer> callbacksCalled = Collections.newSetFromMap(new ConcurrentHashMap<>());
        EventExecutor executor = context.contextAwareEventLoop();
        CountDownLatch latch = new CountDownLatch(18);
        executor.execute(() -> checkCallback(1, context, callbacksCalled, latch));
        executor.schedule(() -> checkCallback(2, context, callbacksCalled, latch), 0, TimeUnit.SECONDS);
        executor.schedule(() -> {
            checkCallback(2, context, callbacksCalled, latch);
            return "success";
        }, 0, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(() -> checkCallback(3, context, callbacksCalled, latch), 0, 1000,
                                     TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> checkCallback(4, context, callbacksCalled, latch), 0, 1000,
                                        TimeUnit.SECONDS);
        executor.submit(() -> checkCallback(5, context, callbacksCalled, latch));
        executor.submit(() -> checkCallback(6, context, callbacksCalled, latch), "success");
        executor.submit(() -> {
            checkCallback(7, context, callbacksCalled, latch);
            return "success";
        });
        executor.invokeAll(makeTaskList(8, 10, context, callbacksCalled, latch));
        executor.invokeAll(makeTaskList(11, 12, context, callbacksCalled, latch), 10000, TimeUnit.SECONDS);
        executor.invokeAny(makeTaskList(13, 13, context, callbacksCalled, latch));
        executor.invokeAny(makeTaskList(14, 14, context, callbacksCalled, latch), 10000, TimeUnit.SECONDS);
        Promise<String> promise = executor.newPromise();
        promise.addListener(f -> checkCallback(15, context, callbacksCalled, latch));
        promise.setSuccess("success");
        executor.newSucceededFuture("success")
                .addListener(f -> checkCallback(16, context, callbacksCalled, latch));
        executor.newFailedFuture(new IllegalArgumentException())
                .addListener(f -> checkCallback(17, context, callbacksCalled, latch));
        ProgressivePromise<String> progressivePromise = executor.newProgressivePromise();
        progressivePromise.addListener(f -> checkCallback(18, context, callbacksCalled, latch));
        progressivePromise.setSuccess("success");
        latch.await();
        eventLoop.shutdownGracefully().sync();
        assertThat(callbacksCalled).containsExactlyElementsOf(IntStream.rangeClosed(1, 18).boxed()::iterator);
    }

    @Test
    public void makeContextAwareExecutor() {
        RequestContext context = createContext();
        Executor executor = context.makeContextAware(MoreExecutors.directExecutor());
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        executor.execute(() -> {
            assertCurrentContext(context);
            assertDepth(1);
            callbackCalled.set(true);
        });
        assertThat(callbackCalled.get()).isTrue();
        assertDepth(0);
    }

    @Test
    public void makeContextAwareCallable() throws Exception {
        RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertCurrentContext(context);
            assertDepth(1);
            return "success";
        }).call();
        assertDepth(0);
    }

    @Test
    public void makeContextAwareRunnable() {
        RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertCurrentContext(context);
            assertDepth(1);
        }).run();
        assertDepth(0);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void makeContextAwareFutureListener() {
        RequestContext context = createContext();
        Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.addListener(context.makeContextAware((FutureListener<String>) f -> {
            assertCurrentContext(context);
            assertDepth(1);
            assertThat(f.getNow()).isEqualTo("success");
        }));
        promise.setSuccess("success");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void makeContextAwareChannelFutureListener() {
        RequestContext context = createContext();
        ChannelPromise promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        promise.addListener(context.makeContextAware((ChannelFutureListener) f -> {
            assertCurrentContext(context);
            assertDepth(1);
            assertThat(f.getNow()).isNull();
        }));
        promise.setSuccess(null);
    }

    @Test
    public void makeContextAwareCompletableFutureInSameThread() throws Exception {
        RequestContext context = createContext();
        CompletableFuture<String> originalFuture = new CompletableFuture<>();
        CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        CompletableFuture<String> resultFuture = contextAwareFuture.whenComplete((result, cause) -> {
            assertThat(result).isEqualTo("success");
            assertThat(cause).isNull();
            assertCurrentContext(context);
            assertDepth(1);
        });
        originalFuture.complete("success");
        assertDepth(0);
        resultFuture.get(); // this will propagate assertions.
    }

    @Test
    public void makeContextAwareCompletableFutureWithExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RequestContext context = createContext();
            Thread testMainThread = Thread.currentThread();
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            CompletableFuture<String> originalFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // In CompletableFuture chaining, if previous callback was already completed,
                    // next chained callback will run in same thread instead of executor's thread.
                    // We should add callbacks before they were completed. This latch is for preventing it.
                    latch.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                final Thread currentThread = Thread.currentThread();
                callbackThread.set(currentThread);
                assertThat(currentThread).isNotSameAs(testMainThread);
                return "success";
            }, executor);

            CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);

            CompletableFuture<String> resultFuture = contextAwareFuture.whenComplete((result, cause) -> {
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread)
                                         .isSameAs(callbackThread.get());
                assertThat(result).isEqualTo("success");
                assertThat(cause).isNull();
                assertCurrentContext(context);
                assertDepth(1);
            });

            latch.countDown();
            resultFuture.get(); // this will wait and propagate assertions.
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void makeContextAwareCompletableFutureWithAsyncChaining() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RequestContext context = createContext();
            Thread testMainThread = Thread.currentThread();
            CountDownLatch latch = new CountDownLatch(1);

            CompletableFuture<String> originalFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread);
                return "success";
            }, executor);

            BiConsumer<String, Throwable> handler = (result, cause) -> {
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread);
                assertThat(result).isEqualTo("success");
                assertThat(cause).isNull();
                assertCurrentContext(context);
            };

            CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
            CompletableFuture<String> future1 = contextAwareFuture.whenCompleteAsync(handler, executor);
            CompletableFuture<String> future2 = future1.whenCompleteAsync(handler, executor);

            latch.countDown(); // fire

            future2.get(); // this will propagate assertions in callbacks if it failed.
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void contextPropagationSameContextAlreadySet() {
        final RequestContext context = createContext();
        try (SafeCloseable ignored = RequestContext.push(context, false)) {
            context.makeContextAware(() -> {
                assertCurrentContext(context);
                // Context was already correct, so handlers were not run (in real code they would already be
                // in the correct state).
                assertDepth(0);
            }).run();
        }
    }

    @Test
    public void contextPropagationDifferentContextAlreadySet() {
        final RequestContext context = createContext();
        final RequestContext context2 = createContext();

        try (SafeCloseable ignored = RequestContext.push(context2)) {
            thrown.expect(IllegalStateException.class);
            context.makeContextAware((Runnable) Assert::fail).run();
        }
    }

    @Test
    public void makeContextAwareRunnableNoContextAwareHandler() {
        RequestContext context = createContext(false);
        context.makeContextAware(() -> {
            assertCurrentContext(context);
            assertDepth(0);
        }).run();
        assertDepth(0);
    }

    @Test
    public void nestedContexts() {
        final RequestContext ctx1 = createContext(true);
        final RequestContext ctx2 = createContext(true);
        final AtomicBoolean nested = new AtomicBoolean();
        try (SafeCloseable ignored = RequestContext.push(ctx1)) {
            assertDepth(1);
            assertThat(ctxStack).containsExactly(ctx1);
            ctx1.onChild((curCtx, newCtx) -> {
                assertThat(curCtx).isSameAs(ctx1);
                assertThat(newCtx).isSameAs(ctx2);
                nested.set(true);
                newCtx.onExit(unused -> nested.set(false));
            });

            assertThat(nested.get()).isFalse();
            try (SafeCloseable ignored2 = RequestContext.push(ctx2)) {
                assertDepth(2);
                assertThat(ctxStack).containsExactly(ctx1, ctx2);
                assertThat(nested.get()).isTrue();
            }
            assertDepth(1);
            assertThat(ctxStack).containsExactly(ctx1);
            assertThat(nested.get()).isFalse();
        }
        assertDepth(0);
    }

    @Test
    public void timedOut() {
        RequestContext ctx = createContext();
        assertThat(ctx.isTimedOut()).isFalse();
        ((AbstractRequestContext) ctx).setTimedOut();
        assertThat(ctx.isTimedOut()).isTrue();
    }

    private void assertDepth(int expectedDepth) {
        assertThat(ctxStack).hasSize(expectedDepth);
    }

    private static List<Callable<String>> makeTaskList(int startId,
                                                       int endId,
                                                       RequestContext context,
                                                       Set<Integer> callbacksCalled,
                                                       CountDownLatch latch) {
        return IntStream.rangeClosed(startId, endId).boxed().map(id -> (Callable<String>) () -> {
            checkCallback(id, context, callbacksCalled, latch);
            return "success";
        }).collect(Collectors.toList());
    }

    private static void checkCallback(int id, RequestContext context, Set<Integer> callbacksCalled,
                                      CountDownLatch latch) {
        assertCurrentContext(context);
        if (!callbacksCalled.contains(id)) {
            callbacksCalled.add(id);
            latch.countDown();
        }
    }

    private static void assertCurrentContext(RequestContext context) {
        final RequestContext ctx = RequestContext.current();
        assertThat(ctx).isSameAs(context);
    }

    private NonWrappingRequestContext createContext() {
        return createContext(true);
    }

    private NonWrappingRequestContext createContext(boolean addContextAwareHandler) {
        final NonWrappingRequestContext ctx = new DummyRequestContext();
        if (addContextAwareHandler) {
            ctx.onEnter(myCtx -> {
                ctxStack.add(myCtx);
                assertThat(myCtx).isSameAs(ctx);
            });
            ctx.onExit(myCtx -> {
                assertThat(ctxStack.remove(ctxStack.size() - 1)).isSameAs(myCtx);
                assertThat(myCtx).isSameAs(ctx);
            });
        }
        return ctx;
    }

    private class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(NoopMeterRegistry.get(), SessionProtocol.HTTP,
                  HttpMethod.GET, "/", null, new DefaultHttpRequest());
        }

        @Override
        public EventLoop eventLoop() {
            return channel.eventLoop();
        }

        @Override
        protected Channel channel() {
            return channel;
        }

        @Override
        public RequestLog log() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestLogBuilder logBuilder() {
            throw new UnsupportedOperationException();
        }
    }
}
