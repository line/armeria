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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

public class AsyncConcurrencyLimitTest {
    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();
    private final ClientRequestContext ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                 .eventLoop(eventLoop.get())
                                                                 .build();

    @Test
    void itShouldExecuteImmediatelyWhilePermitsAreAvailable() {
        final AsyncConcurrencyLimit semaphore = new AsyncConcurrencyLimit(100000, 2, 100);
        assertThat(semaphore.availablePermits()).isEqualTo(2);

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermits()).isEqualTo(1);
            assertThat(semaphore.availablePermits()).isEqualTo(1);
        });

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermits()).isEqualTo(2);
            assertThat(semaphore.availablePermits()).isEqualTo(0);
        });

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermits()).isEqualTo(2);
            assertThat(semaphore.availablePermits()).isEqualTo(0);
        });
    }

    @Test
    void itShouldExecuteDeferredComputationsWhenPermitsAreReleased() {
        final CountingSemaphore semaphore = new CountingSemaphore(new AsyncConcurrencyLimit(100000, 2, 100));

        assertThat(semaphore.availablePermits()).isEqualTo(2);

        semaphore.acquire(ctx);
        semaphore.acquire(ctx);
        semaphore.acquire(ctx);
        semaphore.acquire(ctx);

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(2);
            assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        });

        semaphore.permits.poll().close();

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(3);
            assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        });

        semaphore.permits.poll().close();

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(4);
            assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        });
    }

    private static final class CountingSemaphore {
        private final AsyncConcurrencyLimit sem;
        private final AtomicInteger count = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<SafeCloseable> permits = new ConcurrentLinkedQueue<>();

        private CountingSemaphore(AsyncConcurrencyLimit sem) {
            this.sem = sem;
        }

        public int availablePermits() {
            return sem.availablePermits();
        }

        public CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx) {
            final CompletableFuture<SafeCloseable> fPermit = sem.acquire(ctx);
            fPermit.whenComplete((permit, throwable) -> {
                if (throwable == null) {
                    count.incrementAndGet();
                    permits.offer(permit);
                }
            });
            return fPermit;
        }

        public int acquiredPermits() {
            return sem.acquiredPermits();
        }
    }
}
