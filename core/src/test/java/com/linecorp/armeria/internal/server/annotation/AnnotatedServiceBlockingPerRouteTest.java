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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

class AnnotatedServiceBlockingPerRouteTest {
    private static final AtomicInteger aBlockingCount = new AtomicInteger();
    private static final AtomicInteger bBlockingCount = new AtomicInteger();
    private static final AtomicInteger defaultBlockingCount = new AtomicInteger();

    private static final ScheduledExecutorService aExecutor =
            new ScheduledThreadPoolExecutor(1, ThreadFactories.newThreadFactory("blocking-test-a", true)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    aBlockingCount.incrementAndGet();
                }
            };

    private static final ScheduledExecutorService bExecutor =
            new ScheduledThreadPoolExecutor(1, ThreadFactories.newThreadFactory("blocking-test-b", true)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    bBlockingCount.incrementAndGet();
                }
            };

    private static final ScheduledExecutorService defaultExecutor =
            new ScheduledThreadPoolExecutor(1,
                                            ThreadFactories.newThreadFactory("blocking-test-default", true)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    defaultBlockingCount.incrementAndGet();
                }
            };

    @BeforeEach
    void clear() {
        aBlockingCount.set(0);
        bBlockingCount.set(0);
        defaultBlockingCount.set(0);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService().blockingTaskExecutor(aExecutor, true)
              .build(new MyBlockingAnnotatedServiceA());
            sb.annotatedService().blockingTaskExecutor(bExecutor, false)
              .build(new MyBlockingAnnotatedServiceB());
            sb.annotatedService(new MyBlockingAnnotatedServiceC());

            sb.blockingTaskExecutor(defaultExecutor, true);
        }
    };

    @Test
    void testOnlyEventLoopWithoutBlockingAnnotation() {
        final WebClient client = WebClient.of(server.httpUri());

        AggregatedHttpResponse res = client.execute(RequestHeaders.of(HttpMethod.GET, "/a"))
                                           .aggregate()
                                           .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(aBlockingCount).hasValue(1);
        assertThat(bBlockingCount).hasValue(0);
        assertThat(defaultBlockingCount).hasValue(0);

        res = client.execute(RequestHeaders.of(HttpMethod.GET, "/b"))
                    .aggregate()
                    .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(aBlockingCount).hasValue(1);
        assertThat(bBlockingCount).hasValue(1);
        assertThat(defaultBlockingCount).hasValue(0);

        res = client.execute(RequestHeaders.of(HttpMethod.GET, "/c"))
                    .aggregate()
                    .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(aBlockingCount).hasValue(1);
        assertThat(bBlockingCount).hasValue(1);
        assertThat(defaultBlockingCount).hasValue(1);

        assertThat(aExecutor.isShutdown()).isFalse();
        assertThat(bExecutor.isShutdown()).isFalse();
        assertThat(defaultExecutor.isShutdown()).isFalse();

        server.stop().join();

        assertThat(aExecutor.isShutdown()).isTrue();
        assertThat(bExecutor.isShutdown()).isFalse();
        assertThat(defaultExecutor.isShutdown()).isTrue();
    }

    static class MyBlockingAnnotatedServiceA {
        @Get("/a")
        @Blocking
        public HttpResponse httpResponse() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MyBlockingAnnotatedServiceB {
        @Get("/b")
        @Blocking
        public HttpResponse httpResponse() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MyBlockingAnnotatedServiceC {
        @Get("/c")
        @Blocking
        public HttpResponse httpResponse() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
