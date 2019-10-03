/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

class RequestContextAwareCompletableFutureTest {

    @BeforeAll
    static void checkEnv() {
        assumeThat(SystemInfo.javaVersion()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void minimalCompletionStageUsingToCompletableFutureMutable() throws Exception {
        final RequestContext context =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Integer> originalFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletionStage<Integer> completionStage = contextAwareFuture.minimalCompletionStage();

        assertThat(contextAwareFuture.complete(1)).isTrue();
        assertThat(contextAwareFuture.join()).isEqualTo(1);
        assertThat(contextAwareFuture.getNow(null)).isEqualTo(1);
        assertThat(contextAwareFuture.get()).isEqualTo(1);
        assertThat(completionStage.toCompletableFuture().get()).isEqualTo(1);
    }

    @Test
    void minimalCompletionStageUsingWhenComplete() throws Exception {
        final RequestContext context =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Integer> originalFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletionStage<Integer> completionStage = contextAwareFuture.minimalCompletionStage();

        final AtomicInteger atomicInteger = new AtomicInteger();
        final AtomicReference<Throwable> causeCaptor = new AtomicReference<>();
        completionStage.whenComplete((result, error) -> {
            if (error != null) {
                causeCaptor.set(error);
            } else {
                atomicInteger.set(result);
            }
        });
        contextAwareFuture.complete(1);

        assertThat(contextAwareFuture.join()).isEqualTo(1);
        assertThat(contextAwareFuture.getNow(null)).isEqualTo(1);
        assertThat(contextAwareFuture.get()).isEqualTo(1);
        assertThat(atomicInteger.get()).isEqualTo(1);
        assertThat(causeCaptor.get()).isNull();
    }

    @Test
    void makeContextAwareCompletableFutureUsingCompleteAsync() throws Exception {
        final RequestContext context =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<String> originalFuture = new CompletableFuture<>();
        final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletableFuture<String> resultFuture = contextAwareFuture.completeAsync(() -> "success");

        originalFuture.complete("success");
        assertThat(resultFuture.get()).isEqualTo("success");
    }

    @Test
    void makeContextAwareCompletableFutureUsingCompleteAsyncWithExecutor() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final RequestContext context =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<String> originalFuture = new CompletableFuture<>();
        final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletableFuture<String> resultFuture = contextAwareFuture.completeAsync(() -> "success",
                                                                                        executor);

        originalFuture.complete("success");
        assertThat(resultFuture.get()).isEqualTo("success");
    }
}
