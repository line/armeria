/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;

class RetryableArmeriaServerGracefulShutdownLifecycleTest {

    @Test
    void retryStarting() throws InterruptedException {
        final Server dummyServer = Server.builder()
                                         .service("/", (ctx, req) -> HttpResponse.of("OK"))
                                         .build();
        dummyServer.start().join();

        final Server failingServer = Server.builder().port(dummyServer.activePort())
                                           .service("/", (ctx, req) -> HttpResponse.of("OK"))
                                           .build();

        // Make sure that `failingServer` is unable to start because of the port collision.
        assertThatThrownBy(() -> failingServer.start().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContaining("Address already in use");

        final SmartLifecycle lifecycle =
                new RetryableArmeriaServerGracefulShutdownLifecycle(failingServer, 100);
        final AtomicBoolean success = new AtomicBoolean();

        final CountDownLatch latch = new CountDownLatch(1);
        CommonPools.blockingTaskExecutor().execute(() -> {
            latch.countDown();
            lifecycle.start();
            success.set(true);
        });

        latch.await();
        // Wait for the retry to work
        Thread.sleep(1000);
        dummyServer.stop().join();
        await().untilTrue(success);
    }
}
