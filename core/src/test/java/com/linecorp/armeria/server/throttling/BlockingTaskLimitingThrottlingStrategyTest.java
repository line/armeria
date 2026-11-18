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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BlockingTaskLimitingThrottlingStrategyTest {

    private static final AtomicInteger dynamicLimit = new AtomicInteger(1);
    private static final BlockingTaskExecutor blockingTaskExecutor =
            BlockingTaskExecutor.builder()
                                .numThreads(2)
                                .threadNamePrefix("throttling-blocking-test")
                                .build();

    private static final CountDownLatch taskStartedLatch = new CountDownLatch(1);
    private static final CountDownLatch taskBlockLatch = new CountDownLatch(1);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.blockingTaskExecutor(blockingTaskExecutor, true);
            sb.service("/block", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                ctx.blockingTaskExecutor().execute(() -> {
                    taskStartedLatch.countDown();
                    try {
                        taskBlockLatch.await();
                        future.complete(HttpResponse.of("unblocked"));
                    } catch (InterruptedException e) {
                        future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                    }
                });
                return HttpResponse.of(future);
            });
            sb.service("/test", (ctx, req) -> HttpResponse.of("ok"));
            sb.decorator(ThrottlingService.newDecorator(
                    ThrottlingStrategy.blockingTaskLimiting((IntSupplier) dynamicLimit::get)));
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
    }

    @Test
    void throttlingBasedOnPendingTasks() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        // Baseline: No blocking tasks, should succeed
        assertThat(client.get("/test").aggregate().join().status()).isEqualTo(HttpStatus.OK);

        // Start a blocking task in the executor
        final CompletableFuture<AggregatedHttpResponse> blockingReq = client.get("/block").aggregate();
        taskStartedLatch.await();

        // While task is running, numPendingTasks == 1, which reaches limit 1
        assertThat(blockingTaskExecutor.numPendingTasks()).isOne();

        // New request must be throttled
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

        // Unblock the initial task
        taskBlockLatch.countDown();
        assertThat(blockingReq.join().status()).isEqualTo(HttpStatus.OK);

        // Wait until task counter returns to 0
        org.awaitility.Awaitility.await()
                                .untilAsserted(() -> assertThat(blockingTaskExecutor.numPendingTasks()).isZero());

        // Now requests succeed again
        assertThat(client.get("/test").aggregate().join().status()).isEqualTo(HttpStatus.OK);
    }
}
