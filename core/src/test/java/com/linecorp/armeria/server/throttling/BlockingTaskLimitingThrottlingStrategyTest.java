/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.throttling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BlockingTaskLimitingThrottlingStrategyTest {

    private static final AtomicInteger dynamicLimit = new AtomicInteger(1);
    private static final BlockingTaskExecutor blockingTaskExecutor =
            BlockingTaskExecutor.builder()
                                .numThreads(1)
                                .threadNamePrefix("throttling-blocking-test")
                                .build();

    private static final CountDownLatch task1StartedLatch = new CountDownLatch(1);
    private static final CountDownLatch taskBlockLatch = new CountDownLatch(1);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.blockingTaskExecutor(blockingTaskExecutor, true);
            sb.service("/block1", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                ctx.blockingTaskExecutor().execute(() -> {
                    task1StartedLatch.countDown();
                    try {
                        taskBlockLatch.await();
                        future.complete(HttpResponse.of("unblocked1"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                    }
                });
                return HttpResponse.of(future);
            });
            sb.service("/block2", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                ctx.blockingTaskExecutor().execute(() -> {
                    try {
                        taskBlockLatch.await();
                        future.complete(HttpResponse.of("unblocked2"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                    }
                });
                return HttpResponse.of(future);
            });
            sb.service("/test", (ctx, req) -> HttpResponse.of("ok"));
            sb.decorator(ThrottlingService.newDecorator(
                    ThrottlingStrategy.blockingTaskLimiting(
                            blockingTaskExecutor, (IntSupplier) dynamicLimit::get)));
        }
    };

    @AfterAll
    static void afterAll() {
        taskBlockLatch.countDown();
        blockingTaskExecutor.shutdown();
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> ThrottlingStrategy.blockingTaskLimiting(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThrottlingStrategy.blockingTaskLimiting(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThrottlingStrategy.blockingTaskLimiting((IntSupplier) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ThrottlingStrategy.blockingTaskLimiting((BlockingTaskExecutor) null, 1))
                .isInstanceOf(NullPointerException.class);

        final ScheduledExecutorService nonThreadPool = Executors.newSingleThreadScheduledExecutor();
        try {
            final BlockingTaskExecutor wrapped = BlockingTaskExecutor.of(nonThreadPool);
            assertThatThrownBy(() -> ThrottlingStrategy.blockingTaskLimiting(wrapped, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ThreadPoolExecutor");
        } finally {
            nonThreadPool.shutdownNow();
        }
    }

    @Test
    void throttlingBasedOnQueuedTasks() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final ThreadPoolExecutor pool = blockingTaskExecutor.as(ThreadPoolExecutor.class);
        assertThat(pool).isNotNull();

        // Baseline: No pending tasks in queue, should succeed
        assertThat(client.get("/test").aggregate().join().status()).isEqualTo(HttpStatus.OK);

        // Occupy the single worker thread
        final CompletableFuture<AggregatedHttpResponse> blockingReq1 = client.get("/block1").aggregate();
        task1StartedLatch.await();
        assertThat(pool.getQueue()).isEmpty();

        // Queue a 2nd task while the worker thread is occupied
        final CompletableFuture<AggregatedHttpResponse> blockingReq2 = client.get("/block2").aggregate();
        await().untilAsserted(() -> assertThat(pool.getQueue()).hasSize(1));

        // Queue size == 1 reaches limit 1, so /test must be throttled
        final AggregatedHttpResponse throttledRes = client.get("/test").aggregate().join();
        assertThat(throttledRes.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Dynamically increase limit to 2
        dynamicLimit.set(2);
        final AggregatedHttpResponse allowedRes = client.get("/test").aggregate().join();
        assertThat(allowedRes.status()).isEqualTo(HttpStatus.OK);

        // Reset limit back to 1
        dynamicLimit.set(1);
        final AggregatedHttpResponse throttledAgain = client.get("/test").aggregate().join();
        assertThat(throttledAgain.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Unblock both tasks and verify queue drains and requests succeed again
        taskBlockLatch.countDown();
        assertThat(blockingReq1.join().status()).isEqualTo(HttpStatus.OK);
        assertThat(blockingReq2.join().status()).isEqualTo(HttpStatus.OK);
        await().untilAsserted(() -> assertThat(pool.getQueue()).isEmpty());

        assertThat(client.get("/test").aggregate().join().status()).isEqualTo(HttpStatus.OK);
    }
}
