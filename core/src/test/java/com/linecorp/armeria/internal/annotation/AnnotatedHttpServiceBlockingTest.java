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

package com.linecorp.armeria.internal.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTest.MyAnnotatedService12;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTest.MyAnnotatedService13;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class AnnotatedHttpServiceBlockingTest {

    private static final CountingThreadPoolExecutor executor = new CountingThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
            ThreadFactories.newThreadFactory("blocking-test", true));

    private static final AtomicInteger blockingCount = new AtomicInteger();

    private static class CountingThreadPoolExecutor extends ThreadPoolExecutor {

        CountingThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                                   TimeUnit unit, BlockingQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            blockingCount.incrementAndGet();
        }
    }

    @BeforeEach
    void clear() {
        blockingCount.set(0);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/12", new MyAnnotatedService12(),
                                LoggingService.newDecorator());
            sb.annotatedService("/13", new MyAnnotatedService13(),
                                LoggingService.newDecorator());
            sb.blockingTaskExecutor(executor, false);
        }
    };

    @Test
    public void testOriginBlockingTaskType() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        String path = "/12/httpResponse";
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path);
        AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(0);

        path = "/12/aggregatedHttpResponse";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(0);

        path = "/12/jsonNode";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(0);

        path = "/12/completionStage";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(0);
    }

    @Test
    public void testOnlyBlockingTaskType() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        String path = "/13/httpResponse";
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path);
        AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(1);

        path = "/13/aggregatedHttpResponse";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(2);

        path = "/13/jsonNode";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(3);

        path = "/13/completionStage";
        headers = RequestHeaders.of(HttpMethod.GET, path);
        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(blockingCount).hasValue(4);
    }
}
