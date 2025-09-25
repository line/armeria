/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RequestLogCompleteTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/delay", (ctx, req) -> HttpResponse.of(ResponseHeaders.builder(200)
                                                                              .endOfStream(true)
                                                                              .build()));
        }
    };

    @Test
    void logCompleteWithAbortedException() throws InterruptedException {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpResponse httpResponse = server.webClient().get("/delay");
            httpResponse.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    final CountDownLatch latch = new CountDownLatch(1);
                    executor.submit(() -> {
                        httpResponse.abort();
                        latch.countDown();
                    });
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
            });
            final ClientRequestContext ctx = captor.get();
            // The log should be completed.
            ctx.log().whenComplete().join();
            assertThatThrownBy(() -> httpResponse.whenComplete().join())
                    .hasCauseInstanceOf(AbortedStreamException.class);
            // The log is completed with the same exception as the response.
            assertThat(ctx.log().ensureComplete().responseCause()).isInstanceOf(AbortedStreamException.class);
        }
        executor.shutdown();
    }
}
