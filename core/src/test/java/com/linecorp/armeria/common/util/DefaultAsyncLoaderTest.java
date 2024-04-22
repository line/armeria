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
 * diiibuted under the License is diiibuted on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class DefaultAsyncLoaderTest {

    @Test
    void loader() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader.builder(loadFunc).build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }
    }

    @Test
    void loader_concurrent() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader.builder(loadFunc).build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();
    }

    @Test
    void expireAfterLoad() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireAfterLoad(Duration.ofSeconds(3))
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        Thread.sleep(3500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isEqualTo(2);
            assertThat(loadCounter.get()).isEqualTo(2);
        }
    }

    @Test
    void expireAfterLoad_concurrent() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireAfterLoad(Duration.ofSeconds(3))
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        Thread.sleep(3500);

        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isEqualTo(2);
                assertThat(loadCounter.get()).isEqualTo(2);
                latch2.countDown();
            });
        }
        latch2.await();
    }

    @Test
    void expireIf() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AtomicBoolean expired = new AtomicBoolean();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> expired.get())
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        expired.set(true);
        assertThat(loader.get().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.get().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isEqualTo(3);
            assertThat(loadCounter.get()).isEqualTo(3);
        }
    }

    @Test
    void expireIf_concurrent() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AtomicBoolean expired = new AtomicBoolean();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> expired.get())
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        expired.set(true);
        assertThat(loader.get().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.get().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isEqualTo(3);
                assertThat(loadCounter.get()).isEqualTo(3);
                latch2.countDown();
            });
        }
        latch2.await();
    }

    @Test
    void expires() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AtomicBoolean expired = new AtomicBoolean();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireAfterLoad(Duration.ofSeconds(3))
                .expireIf(i -> expired.get())
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        Thread.sleep(3500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isEqualTo(2);
            assertThat(loadCounter.get()).isEqualTo(2);
        }

        expired.set(true);
        assertThat(loader.get().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);
        assertThat(loader.get().join()).isEqualTo(4);
        assertThat(loadCounter.get()).isEqualTo(4);

        expired.set(false);
        assertThat(loader.get().join()).isEqualTo(4);
        assertThat(loadCounter.get()).isEqualTo(4);

        Thread.sleep(3500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.get().join()).isEqualTo(5);
            assertThat(loadCounter.get()).isEqualTo(5);
        }
    }

    @Test
    void expires_concurrent() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AtomicBoolean expired = new AtomicBoolean();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireAfterLoad(Duration.ofSeconds(3))
                .expireIf(i -> expired.get())
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        expired.set(true);
        assertThat(loader.get().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.get().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        assertThat(loader.get().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        Thread.sleep(3500);

        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.get().join()).isEqualTo(4);
                assertThat(loadCounter.get()).isEqualTo(4);
                latch2.countDown();
            });
        }
        latch2.await();
    }

    @Test
    void loader_thrown() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            throw new IllegalStateException();
        };
        final AsyncLoader<Integer> loader = AsyncLoader.builder(loadFunc).build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void loader_future_exception() {
        final Function<Object, CompletableFuture<Object>> loadFunc = obj -> {
            final CompletableFuture<Object> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException());
            return future;
        };
        final AsyncLoader<Object> loader = AsyncLoader.builder(loadFunc).build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void loader_null() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> null;
        final AsyncLoader<Integer> loader = AsyncLoader.builder(loadFunc).build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(NullPointerException.class));
    }

    @Test
    void exceptionHandler_loader_thrown() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            throw new IllegalStateException();
        };
        final AtomicInteger handleExceptionCounter = new AtomicInteger();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(IllegalStateException.class);
                    return UnmodifiableFuture.completedFuture(handleExceptionCounter.incrementAndGet());
                })
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(handleExceptionCounter.get()).isOne();
        }
    }

    @Test
    void exceptionHandler_loader_future_exception() {
        final Function<Object, CompletableFuture<Object>> loadFunc = obj -> {
            final CompletableFuture<Object> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException());
            return future;
        };
        final AtomicInteger handleExceptionCounter = new AtomicInteger();
        final AsyncLoader<Object> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(IllegalStateException.class);
                    return UnmodifiableFuture.completedFuture(handleExceptionCounter.incrementAndGet());
                })
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.get().join()).isEqualTo(1);
            assertThat(handleExceptionCounter.get()).isOne();
        }
    }

    @Test
    void exceptionHandler_loader_null() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> null;
        final AtomicInteger handleExceptionCounter = new AtomicInteger();
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(NullPointerException.class);
                    return UnmodifiableFuture.completedFuture(handleExceptionCounter.incrementAndGet());
                })
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.get().join()).isOne();
            assertThat(handleExceptionCounter.get()).isOne();
        }
    }

    @Test
    void exceptionHandler_thrown() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            throw new IllegalStateException();
        };
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(IllegalStateException.class);
                    throw new IllegalStateException();
                })
                .build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void exceptionHandler_future_exception() {
        final Function<Object, CompletableFuture<Object>> loadFunc = obj -> {
            final CompletableFuture<Object> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException());
            return future;
        };
        final AsyncLoader<Object> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(IllegalStateException.class);
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    future.completeExceptionally(new IllegalStateException());
                    return future;
                })
                .build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void exceptionHandler_null() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> null;
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .exceptionHandler((cause, cache) -> {
                    assertThat(cause).isInstanceOf(NullPointerException.class);
                    return null;
                })
                .build();

        assertThatThrownBy(() -> loader.get().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(NullPointerException.class));
    }
}
