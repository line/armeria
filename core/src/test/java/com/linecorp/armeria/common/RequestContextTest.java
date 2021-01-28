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
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ProgressivePromise;
import io.netty.util.concurrent.Promise;

class RequestContextTest {

    @Test
    void run() {
        final RequestContext ctx = createContext();
        ctx.run(() -> {
            assertCurrentContext(ctx);
        });
        assertCurrentContext(null);
    }

    @Test
    void call() throws Exception {
        final RequestContext ctx = createContext();
        ctx.call(() -> {
            assertCurrentContext(ctx);
            return "success";
        });
        assertCurrentContext(null);
    }

    @Test
    void contextAwareEventExecutor() throws Exception {
        final RequestContext context = createContext();
        final Set<Integer> callbacksCalled = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final EventExecutor executor = context.eventLoop();
        final CountDownLatch latch = new CountDownLatch(18);
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
        final Promise<String> promise = executor.newPromise();
        promise.addListener(f -> checkCallback(15, context, callbacksCalled, latch));
        promise.setSuccess("success");
        executor.newSucceededFuture("success")
                .addListener(f -> checkCallback(16, context, callbacksCalled, latch));
        executor.newFailedFuture(new IllegalArgumentException())
                .addListener(f -> checkCallback(17, context, callbacksCalled, latch));
        final ProgressivePromise<String> progressivePromise = executor.newProgressivePromise();
        progressivePromise.addListener(f -> checkCallback(18, context, callbacksCalled, latch));
        progressivePromise.setSuccess("success");
        latch.await();
        await().untilAsserted(() -> {
            assertThat(callbacksCalled).containsExactlyElementsOf(IntStream.rangeClosed(1, 18)
                                                                           .boxed()::iterator);
        });
    }

    @Test
    void makeContextAwareExecutor() {
        final RequestContext context = createContext();
        final Executor executor = context.makeContextAware(MoreExecutors.directExecutor());
        final AtomicBoolean callbackCalled = new AtomicBoolean(false);
        executor.execute(() -> {
            assertCurrentContext(context);
            callbackCalled.set(true);
        });
        assertThat(callbackCalled.get()).isTrue();
        assertCurrentContext(null);
    }

    @Test
    void makeContextAwareCallable() throws Exception {
        final RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertCurrentContext(context);
            return "success";
        }).call();
        assertCurrentContext(null);
    }

    @Test
    void makeContextAwareRunnable() {
        final RequestContext context = createContext();
        context.makeContextAware(() -> {
            assertCurrentContext(context);
        }).run();
        assertCurrentContext(null);
    }

    @Test
    void contextAwareScheduledExecutorService() throws Exception {
        final RequestContext context = createContext();
        final ScheduledExecutorService executor = context.makeContextAware(
                Executors.newSingleThreadScheduledExecutor());
        final ScheduledFuture<?> future = executor.schedule(() -> {
            assertCurrentContext(context);
        }, 100, TimeUnit.MILLISECONDS);
        future.get();
        assertCurrentContext(null);
    }

    @Test
    void makeContextAwareCompletableFutureInSameThread() throws Exception {
        final RequestContext context = createContext();
        final CompletableFuture<String> originalFuture = new CompletableFuture<>();
        final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletableFuture<String> resultFuture = contextAwareFuture.whenComplete((result, cause) -> {
            assertThat(result).isEqualTo("success");
            assertThat(cause).isNull();
            assertCurrentContext(context);
        });
        originalFuture.complete("success");
        assertCurrentContext(null);
        resultFuture.get(); // this will propagate assertions.
    }

    @Test
    void makeContextAwareCompletableFutureWithExecutor() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final RequestContext context = createContext();
            final Thread testMainThread = Thread.currentThread();
            final AtomicReference<Thread> callbackThread = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);

            final CompletableFuture<String> originalFuture = CompletableFuture.supplyAsync(() -> {
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

            final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);

            final CompletableFuture<String> resultFuture = contextAwareFuture.whenComplete((result, cause) -> {
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread)
                                         .isSameAs(callbackThread.get());
                assertThat(result).isEqualTo("success");
                assertThat(cause).isNull();
                assertCurrentContext(context);
            });

            latch.countDown();
            resultFuture.get(); // this will wait and propagate assertions.
            assertCurrentContext(null);
        } finally {
            shutdownAndAwaitTermination(executor);
        }
    }

    @Test
    void makeContextAwareCompletableFutureWithAsyncChaining() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final RequestContext context = createContext();
            final Thread testMainThread = Thread.currentThread();
            final CountDownLatch latch = new CountDownLatch(1);

            final CompletableFuture<String> originalFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread);
                return "success";
            }, executor);

            final BiConsumer<String, Throwable> handler = (result, cause) -> {
                final Thread currentThread = Thread.currentThread();
                assertThat(currentThread).isNotSameAs(testMainThread);
                assertThat(result).isEqualTo("success");
                assertThat(cause).isNull();
                assertCurrentContext(context);
            };

            final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
            final CompletableFuture<String> future1 = contextAwareFuture.whenCompleteAsync(handler, executor);
            final CompletableFuture<String> future2 = future1.whenCompleteAsync(handler, executor);

            latch.countDown(); // fire

            future2.get(); // this will propagate assertions in callbacks if it failed.
        } finally {
            shutdownAndAwaitTermination(executor);
        }
    }

    @Test
    void replace() {
        final RequestContext ctx1 = createContext();
        final RequestContext ctx2 = createContext();
        try (SafeCloseable ignored = ctx1.push()) {
            assertCurrentContext(ctx1);
            try (SafeCloseable ignored2 = ctx2.replace()) {
                assertCurrentContext(ctx2);
            }
            assertCurrentContext(ctx1);
        }
        assertCurrentContext(null);
    }

    @Test
    void makeContextAwareLogger() {
        final RequestContext ctx = createContext();
        final Logger logger = LoggerFactory.getLogger(RequestContextTest.class);
        final Logger contextAwareLogger = ctx.makeContextAware(logger);
        assertThat(ctx.makeContextAware(contextAwareLogger)).isSameAs(contextAwareLogger);
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

    private static void assertCurrentContext(@Nullable RequestContext context) {
        final RequestContext ctx = RequestContext.currentOrNull();
        assertThat(ctx).isSameAs(context);
    }

    private static void shutdownAndAwaitTermination(ExecutorService executor) {
        assertThat(MoreExecutors.shutdownAndAwaitTermination(executor, 10, TimeUnit.SECONDS)).isTrue();
    }

    private static ServiceRequestContext createContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
