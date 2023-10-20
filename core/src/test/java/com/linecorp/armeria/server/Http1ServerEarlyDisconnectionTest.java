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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@FlakyTest
class Http1ServerEarlyDisconnectionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();
                writer.write(ResponseHeaders.builder(200)
                                            .contentLength(10)
                                            .build());
                ctx.blockingTaskExecutor().execute(() -> {
                    writer.write(HttpData.ofUtf8("0123456789"));

                    // Wait for the client to close the connection.
                    // Note: The sleep duration should be less than 1 second after which `ServerHandler`
                    //       calls `cleanup()` to remove `unfinishedRequests`.
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    writer.close();
                });

                return writer;
            });
        }
    };

    private static CountDownLatch latch;

    @BeforeAll
    static void beforeAll() {
        latch = new CountDownLatch(1);
    }

    @Test
    void closeConnectionWhenAllContentAreReceived() throws InterruptedException {
        final ClientFactory clientFactory = ClientFactory.builder().build();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                          .factory(clientFactory)
                                          .build();
        final HttpResponse response = client.get("/");
        final SplitHttpResponse split = response.split();
        final ResponseHeaders headers = split.headers().join();
        final long contentLength = headers.contentLength();
        split.body().subscribe(new Subscriber<HttpData>() {

            private int received;

            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                received += httpData.length();
                if (received >= contentLength) {
                    // All data is received, so it should be safe to close the connection.
                    BlockingUtils.blockingRun(() -> {
                        clientFactory.close();
                        latch.countDown();
                    });
                }
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.responseCause()).isNull();
    }
}
