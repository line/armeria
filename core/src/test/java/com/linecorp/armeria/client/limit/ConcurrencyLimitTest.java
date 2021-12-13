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

package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.util.CachingSupplier;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

class ConcurrencyLimitTest {

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void testConcurrencyLimit() throws InterruptedException {
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, 2, 1, 100000);
        assertThat(limit.availablePermits()).isEqualTo(2);

        final SafeCloseable acquired1 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(1);

        final SafeCloseable acquired2 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        final CompletableFuture<SafeCloseable> acquired3Future = limit.acquire(ctx);
        Thread.sleep(200);
        assertThat(acquired3Future.isDone()).isFalse();
        // Parameters are still the same.
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        assertThatThrownBy(() -> limit.acquire(ctx).join())
                .hasCauseInstanceOf(TooManyPendingAcquisitionsException.class);

        acquired1.close();
        // acquired3Future is done now.
        acquired3Future.join();
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        acquired2.close();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(1);
    }

    @Test
    void testConcurrencyLimit_dynamicLimit() throws InterruptedException {
        final CachingSupplier<Integer> maxConcurrency = CachingSupplier.of(3);
        final DefaultConcurrencyLimit limit =
                new DefaultConcurrencyLimit(ctx -> true, maxConcurrency, 1, 100000);
        assertThat(limit.maxConcurrency()).isEqualTo(3);
        assertThat(limit.availablePermits()).isEqualTo(3);

        maxConcurrency.set(0);
        assertThat(limit.maxConcurrency()).isEqualTo(0);

        maxConcurrency.set(-1);
        assertThat(limit.maxConcurrency()).isEqualTo(0);

        maxConcurrency.set(null);
        assertThat(limit.maxConcurrency()).isEqualTo(0);

        maxConcurrency.set(2);
        assertThat(limit.maxConcurrency()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(2);

        final SafeCloseable acquired1 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(1);

        maxConcurrency.set(1);
        assertThat(limit.maxConcurrency()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(0);
        maxConcurrency.set(2);
        assertThat(limit.maxConcurrency()).isEqualTo(2);

        final SafeCloseable acquired2 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        maxConcurrency.set(1);
        assertThat(limit.availablePermits()).isEqualTo(0);
        maxConcurrency.set(2);

        final CompletableFuture<SafeCloseable> acquired3Future = limit.acquire(ctx);
        Thread.sleep(200);
        assertThat(acquired3Future.isDone()).isFalse();
        // Parameters are still the same.
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        assertThatThrownBy(() -> limit.acquire(ctx).join())
                .hasCauseInstanceOf(TooManyPendingAcquisitionsException.class);

        acquired1.close();
        // acquired3Future is done now.
        acquired3Future.join();
        assertThat(limit.acquiredPermits()).isEqualTo(2);
        assertThat(limit.availablePermits()).isEqualTo(0);

        acquired2.close();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(1);
    }

    @Test
    void concurrencyLimitTimeout() throws InterruptedException {
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, 1, 1, 500);
        assertThat(limit.availablePermits()).isEqualTo(1);

        final SafeCloseable acquired1 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(0);

        final CompletableFuture<SafeCloseable> acquired2Future = limit.acquire(ctx);
        Thread.sleep(100);
        assertThat(acquired2Future.isDone()).isFalse();
        // Parameters are still the same.
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(0);

        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThatThrownBy(
                       acquired2Future::join).hasCauseInstanceOf(ConcurrencyLimitTimeoutException.class));
    }

    @Test
    void acquireCallbackIsExecutedWithTheMatchingContext() throws InterruptedException {
        final DefaultConcurrencyLimit limit = new DefaultConcurrencyLimit(ctx -> true, 1, 3, 10000);
        assertThat(limit.availablePermits()).isEqualTo(1);

        final SafeCloseable acquired1 = limit.acquire(ctx).join();
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(0);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ClientRequestContext ctx4 = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                              .eventLoop(ctx2.eventLoop().withoutContext())
                                                              .build();

        final CompletableFuture<SafeCloseable> acquired2Future = limit.acquire(ctx2);
        final CompletableFuture<SafeCloseable> acquired3Future = limit.acquire(ctx3);
        final CompletableFuture<SafeCloseable> acquired4Future = limit.acquire(ctx4);
        Thread.sleep(100);
        assertThat(acquired2Future.isDone()).isFalse();
        assertThat(acquired3Future.isDone()).isFalse();
        assertThat(acquired4Future.isDone()).isFalse();
        // Parameters are still the same.
        assertThat(limit.acquiredPermits()).isEqualTo(1);
        assertThat(limit.availablePermits()).isEqualTo(0);

        final AtomicInteger counter = new AtomicInteger();
        addCallbackToCheckCurrentCtx(ctx2, acquired2Future, counter);
        addCallbackToCheckCurrentCtx(ctx3, acquired3Future, counter);
        addCallbackToCheckCurrentCtx(ctx4, acquired4Future, counter);

        acquired1.close(); // This triggers to call the callback of acquired2Future.
        await().until(acquired2Future::isDone);
        assertThat(acquired3Future.isDone()).isFalse();
        assertThat(acquired4Future.isDone()).isFalse();

        acquired2Future.join().close(); // This triggers to call the callback of acquired3Future
        await().until(acquired3Future::isDone);
        assertThat(acquired4Future.isDone()).isFalse();
        // Use the eventLoop of ctx3 to check
        // - the callback of acquired4Future is executed by the same eventLoop that has the different ctx(ctx4)
        ctx3.eventLoop().execute(() -> acquired3Future.join().close());

        await().until(() -> counter.get() == 3);
    }

    private static void addCallbackToCheckCurrentCtx(ClientRequestContext ctx2,
                                                     CompletableFuture<SafeCloseable> acquiredFuture,
                                                     AtomicInteger counter) {
        acquiredFuture.thenRun(() -> {
            if (RequestContext.currentOrNull() == ctx2) {
                counter.incrementAndGet();
            }
        });
    }
}
