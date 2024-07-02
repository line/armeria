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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class DefaultAsyncLoaderTest {

    @Test
    void loader() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> false)
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }
    }

    @Test
    void loader_concurrent() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> false)
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isOne();
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
                .expireAfterLoad(Duration.ofSeconds(1))
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        Thread.sleep(1500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(2);
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
                .expireAfterLoad(Duration.ofSeconds(1))
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        Thread.sleep(1500);

        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isEqualTo(2);
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
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        expired.set(true);
        assertThat(loader.load().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.load().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(3);
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
                assertThat(loader.load().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        expired.set(true);
        assertThat(loader.load().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.load().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isEqualTo(3);
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
                .expireAfterLoad(Duration.ofSeconds(1))
                .expireIf(i -> expired.get())
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        Thread.sleep(1500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(2);
            assertThat(loadCounter.get()).isEqualTo(2);
        }

        expired.set(true);
        assertThat(loader.load().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);
        assertThat(loader.load().join()).isEqualTo(4);
        assertThat(loadCounter.get()).isEqualTo(4);

        expired.set(false);
        assertThat(loader.load().join()).isEqualTo(4);
        assertThat(loadCounter.get()).isEqualTo(4);

        Thread.sleep(1500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(5);
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
                .expireAfterLoad(Duration.ofSeconds(1))
                .expireIf(i -> expired.get())
                .build();
        final ExecutorService service = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(5);

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isOne();
                assertThat(loadCounter.get()).isOne();
                latch.countDown();
            });
        }
        latch.await();

        expired.set(true);
        assertThat(loader.load().join()).isEqualTo(2);
        assertThat(loadCounter.get()).isEqualTo(2);
        assertThat(loader.load().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        expired.set(false);
        assertThat(loader.load().join()).isEqualTo(3);
        assertThat(loadCounter.get()).isEqualTo(3);

        Thread.sleep(1500);

        final CountDownLatch latch2 = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            service.execute(() -> {
                assertThat(loader.load().join()).isEqualTo(4);
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
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
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
        final AsyncLoader<Object> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void loader_null() {
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> null;
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
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
                .expireIf(i -> false)
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.load().join()).isOne();
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
                .expireIf(i -> false)
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.load().join()).isEqualTo(1);
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
                .expireIf(i -> false)
                .build();

        assertThat(handleExceptionCounter.get()).isZero();
        for (int i = 0; i <= 5; i++) {
            assertThat(loader.load().join()).isOne();
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
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
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
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
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
                .expireIf(i -> false)
                .build();

        assertThatThrownBy(() -> loader.load().join()).isInstanceOfSatisfying(
                CompletionException.class,
                ex -> assertThat(ex.getCause()).isInstanceOf(NullPointerException.class));
    }

    @Test
    void refreshIf() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final AtomicBoolean refresh = new AtomicBoolean();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .refreshIf(i -> refresh.get())
                .expireIf(i -> false)
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        // refresh 1
        refresh.set(true);
        await().untilAsserted(() -> {
            final int val = loader.load().join();
            refresh.set(false);
            assertThat(val).isEqualTo(2);
        });

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(2);
            assertThat(loadCounter.get()).isEqualTo(2);
        }

        // refresh 2
        refresh.set(true);
        await().untilAsserted(() -> {
            final int val = loader.load().join();
            refresh.set(false);
            assertThat(val).isEqualTo(3);
        });

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(3);
            assertThat(loadCounter.get()).isEqualTo(3);
        }
    }

    @Test
    void refreshIf_thrown() throws InterruptedException {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i ->
                UnmodifiableFuture.completedFuture(loadCounter.incrementAndGet());
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireAfterLoad(Duration.ofSeconds(1))
                .refreshIf(i -> {
                    throw new IllegalStateException();
                })
                .build();

        assertThat(loadCounter.get()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isOne();
            assertThat(loadCounter.get()).isOne();
        }

        Thread.sleep(1500);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(2);
            assertThat(loadCounter.get()).isEqualTo(2);
        }
    }

    @Test
    void refreshIf_loader_future_exception() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = obj -> {
            final int loadCount = loadCounter.incrementAndGet();
            final CompletableFuture<Integer> future = new CompletableFuture<>();
            if (loadCount == 2) {
                future.completeExceptionally(new IllegalStateException());
            } else {
                future.complete(loadCount);
            }
            return future;
        };
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .refreshIf(i -> i == 1)
                .expireIf(i -> false)
                .build();

        assertThat(loadCounter.get()).isZero();
        assertThat(loader.load().join()).isOne();
        assertThat(loadCounter.get()).isOne();

        // refresh 1 failed
        assertThat(loader.load().join()).isOne();
        await().untilAsserted(() -> assertThat(loadCounter.get()).isEqualTo(2));

        // refresh 2 success
        await().untilAsserted(() -> assertThat(loader.load().join()).isEqualTo(3));
        assertThat(loadCounter.get()).isEqualTo(3);

        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(3);
            assertThat(loadCounter.get()).isEqualTo(3);
        }
    }

    @Test
    void refreshIf_expired_wait_refresh() {
        final AtomicInteger loadCounter = new AtomicInteger();
        final AtomicBoolean refresh = new AtomicBoolean();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            final int loadCount = loadCounter.incrementAndGet();
            if (refresh.get()) {
                return UnmodifiableFuture.completedFuture(100);
            }
            return UnmodifiableFuture.completedFuture(loadCount);
        };
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> i == 1)
                .refreshIf(i -> refresh.get())
                .build();

        assertThat(loadCounter.get()).isZero();
        assertThat(loader.load().join()).isOne();
        assertThat(loadCounter.get()).isOne();

        refresh.set(true);
        assertThat(loader.load().join()).isEqualTo(100);
        assertThat(loadCounter.get()).isEqualTo(2);

        refresh.set(false);
        for (int i = 0; i < 5; i++) {
            assertThat(loader.load().join()).isEqualTo(100);
            assertThat(loadCounter.get()).isEqualTo(2);
        }
    }

    @Test
    void nullCache() {
        final AtomicBoolean expire = new AtomicBoolean();
        final AtomicReference<Integer> cache = new AtomicReference<>();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            return UnmodifiableFuture.completedFuture(cache.get());
        };
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .expireIf(i -> expire.get())
                .build();

        assertThat(loader.load().join()).isNull();
        assertThat(loader.load().join()).isNull();
        cache.set(1);
        expire.set(true);
        assertThat(loader.load().join()).isOne();
        cache.set(0);
        assertThat(loader.load().join()).isZero();
        cache.set(null);
        assertThat(loader.load().join()).isNull();
        cache.set(0);
        assertThat(loader.load().join()).isZero();
    }

    @Test
    void refreshWhileCacheIsValid() {
        final AtomicInteger refreshCounter = new AtomicInteger();
        final Function<Integer, CompletableFuture<Integer>> loadFunc = i -> {
            if (i == null) {
                return UnmodifiableFuture.completedFuture(1);
            }
            refreshCounter.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Sleep for 1 minute to fail the test if the loader waits for the refresh to complete.
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return i + 1;
            });
        };
        final AsyncLoader<Integer> loader = AsyncLoader
                .builder(loadFunc)
                .refreshIf(i -> i == 1)
                .expireIf(i -> false)
                .build();
        assertThat(loader.load().join()).isOne();
        assertThat(refreshCounter).hasValue(0);
        assertThat(loader.load().join()).isOne();
        assertThat(refreshCounter).hasValue(1);
        // Should not wait for the refresh to complete.
        assertThat(loader.load().join()).isOne();
        assertThat(refreshCounter).hasValue(1);
    }
}
