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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceBlockingTest {
    private static final AtomicInteger blockingCount = new AtomicInteger();

    private static final ScheduledExecutorService executor =
            new ScheduledThreadPoolExecutor(1, ThreadFactories.newThreadFactory("blocking-test", true)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    blockingCount.incrementAndGet();
                }
            };

    @BeforeEach
    void clear() {
        blockingCount.set(0);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/myEvenLoop", new MyAnnotatedService());
            sb.annotatedService("/myBlocking", new MyBlockingAnnotatedService());
            sb.annotatedService("/blockingService", new BlockingClassAnnotatedService());
            sb.annotatedService()
              .pathPrefix("/useBlockingExecutor")
              .useBlockingTaskExecutor(true)
              .build(new MyAnnotatedService());
            sb.blockingTaskExecutor(executor, true);
        }
    };

    static class MyAnnotatedService {

        @Get("/httpResponse")
        public HttpResponse httpResponse() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/aggregatedHttpResponse")
        public AggregatedHttpResponse aggregatedHttpResponse() {
            return AggregatedHttpResponse.of(HttpStatus.OK);
        }

        @Get("/jsonNode")
        public JsonNode jsonNode() {
            return TextNode.valueOf("Armeria");
        }

        @Get("/completionStage")
        public CompletionStage<String> completionStage() {
            return CompletableFuture.supplyAsync(() -> "Armeria");
        }
    }

    static class MyBlockingAnnotatedService {

        @Get("/httpResponse")
        @Blocking
        public HttpResponse httpResponse() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/aggregatedHttpResponse")
        @Blocking
        public AggregatedHttpResponse aggregatedHttpResponse() {
            return AggregatedHttpResponse.of(HttpStatus.OK);
        }

        @Get("/jsonNode")
        @Blocking
        public JsonNode jsonNode() {
            return TextNode.valueOf("Armeria");
        }

        @Get("/completionStage")
        @Blocking
        public CompletionStage<String> completionStage() {
            return CompletableFuture.supplyAsync(() -> "Armeria");
        }
    }

    @Blocking
    static class BlockingClassAnnotatedService {
        @Get("/block")
        public HttpResponse block() {
            assertThat(Thread.currentThread().getName()).startsWith("blocking-test");
            return HttpResponse.of(HttpStatus.OK);
        }

        @Blocking
        @Get("/duplicated")
        public String duplicated() {
            assertThat(Thread.currentThread().getName()).startsWith("blocking-test");
            return "Hello";
        }
    }

    @ParameterizedTest
    @CsvSource({
            "/myEvenLoop/httpResponse, 0",
            "/myEvenLoop/aggregatedHttpResponse, 0",
            "/myEvenLoop/jsonNode, 0",
            "/myEvenLoop/completionStage, 0"
    })
    void testOnlyEventLoopWithoutBlockingAnnotation(String path, Integer count) throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path);
        final AggregatedHttpResponse res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(count);
    }

    @ParameterizedTest
    @CsvSource({
            "/myBlocking/httpResponse, 1",
            "/myBlocking/aggregatedHttpResponse, 1",
            "/myBlocking/jsonNode, 1",
            "/myBlocking/completionStage, 1",
            "/blockingService/block, 1",
            "/blockingService/duplicated, 1",
            "/useBlockingExecutor/httpResponse, 1",
            "/useBlockingExecutor/aggregatedHttpResponse, 1",
            "/useBlockingExecutor/jsonNode, 1",
            "/useBlockingExecutor/completionStage, 1"
    })
    void testOnlyBlockingWithBlockingAnnotation(String path, Integer count) throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path);
        final AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(count);
    }
}
