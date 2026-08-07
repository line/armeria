/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BlockingTaskLimitingThrottlingStrategyTest {
    private static final IntSupplier limitSupplier = () -> 2;

    private static final BlockingTaskExecutor executor =
            BlockingTaskExecutor.builder()
                                .threadNamePrefix("blocking-task-executor")
                                .numThreads(4)
                                .build();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.blockingTaskExecutor(executor, true);
            sb.annotatedService(new BlockingAnnotatedService())
              .decorator(ThrottlingService.newDecorator(
                      ThrottlingStrategy.blockingTaskLimiting(limitSupplier, null)))
              .decorator(LoggingService.newDecorator());
        }
    };

    @AfterAll
    public static void after() {
        executor.shutdown();
    }

    @Test
    void serve() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(LoggingClient.newDecorator())
                                          .build();
        assertThat(client.get("/blocking-api").aggregate().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void throttle() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(LoggingClient.newDecorator())
                                          .build();

        await().pollInterval(100L, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                                  final HttpResponse ignore = client.get("/blocking-api");
                                  assertThat(client.get("/blocking-api").aggregate().join().status())
                                          .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                              }
               );
    }

    static class BlockingAnnotatedService {
        @Get("/blocking-api")
        @Blocking
        public HttpResponse response() {
            System.out.println("thread name: " + Thread.currentThread().getName());
            return HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofSeconds(1),
                                        RequestContext.current().root().blockingTaskExecutor());
        }
    }
}
