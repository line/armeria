/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.RequestContext.PushHandle;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

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

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Mock
    private Channel channel;

    private final AtomicBoolean entered = new AtomicBoolean();

    @Test
    public void contextAwareEventExecutor() throws Exception {
        EventLoop eventLoop = new DefaultEventLoop();
        when(channel.eventLoop()).thenReturn(eventLoop);
        RequestContext context = createContext();
        Set<Integer> callbacksCalled = new ConcurrentHashSet<>();
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
        assertEquals(IntStream.rangeClosed(1, 18).boxed().collect(Collectors.toSet()), callbacksCalled);
    }

    @Test
    public void makeContextAwareExecutor() {
        RequestContext context = createContext();
        Executor executor = context.makeContextAware(MoreExecutors.directExecutor());
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        executor.execute(() -> {
            assertEquals(context, RequestContext.current());
            assertTrue(entered.get());
            callbackCalled.set(true);
        });
        assertTrue(callbackCalled.get());
        assertFalse(entered.get());
    }

    @Test
    public void makeContextAwareCallable() throws Exception {
        RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertEquals(context, RequestContext.current());
            assertTrue(entered.get());
            return "success";
        }).call();
        assertFalse(entered.get());
    }

    @Test
    public void makeContextAwareRunnable() {
        RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertEquals(context, RequestContext.current());
            assertTrue(entered.get());
        }).run();
        assertFalse(entered.get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void makeContextAwareFutureListener() {
        RequestContext context = createContext();
        Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.addListener(context.makeContextAware((FutureListener<String>) f -> {
            assertEquals(context, RequestContext.current());
            assertTrue(entered.get());
        }));
        promise.setSuccess("success");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void makeContextAwareChannelFutureListener() {
        RequestContext context = createContext();
        ChannelPromise promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        promise.addListener(context.makeContextAware((ChannelFutureListener) f -> {
            assertEquals(context, RequestContext.current());
            assertTrue(entered.get());
        }));
        promise.setSuccess(null);
    }

    @Test
    public void contextPropagationSameContextAlreadySet() {
        final RequestContext context = createContext();
        try (PushHandle ignored = RequestContext.push(context, false)) {
            context.makeContextAware(() -> {
                assertEquals(context, RequestContext.current());
                // Context was already correct, so handlers were not run (in real code they would already be
                // in the correct state).
                assertFalse(entered.get());
            }).run();
        }
    }

    @Test
    public void contextPropagationDifferentContextAlreadySet() {
        final RequestContext context = createContext();
        final RequestContext context2 = createContext();

        try (PushHandle ignored = RequestContext.push(context2)) {
            thrown.expect(IllegalStateException.class);
            context.makeContextAware((Runnable) Assert::fail).run();
        }
    }

    @Test
    public void makeContextAwareRunnableNoContextAwareHandler() {
        RequestContext context = createContext(false);
        context.makeContextAware(() -> {
            assertEquals(context, RequestContext.current());
            assertFalse(entered.get());
        }).run();
        assertFalse(entered.get());
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
        assertEquals(context, RequestContext.current());
        if (!callbacksCalled.contains(id)) {
            callbacksCalled.add(id);
            latch.countDown();
        }
    }

    private RequestContext createContext() {
        return createContext(true);
    }

    private RequestContext createContext(boolean addContextAwareHandler) {
        final RequestContext ctx = new DummyRequestContext();
        if (addContextAwareHandler) {
            ctx.onEnter(() -> entered.set(true));
            ctx.onExit(() -> entered.set(false));
        }
        return ctx;
    }

    private class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(SessionProtocol.HTTP, "GET", "/", new DefaultHttpRequest());
        }

        @Override
        public EventLoop eventLoop() {
            return channel.eventLoop();
        }

        @Override
        public RequestLogBuilder requestLogBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseLogBuilder responseLogBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<RequestLog> requestLogFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ResponseLog> responseLogFuture() {
            throw new UnsupportedOperationException();
        }
    }
}
