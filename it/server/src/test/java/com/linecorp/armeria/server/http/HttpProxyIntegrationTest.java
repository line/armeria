/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

/** Test for issues that may happen when doing simple proxying. */
public class HttpProxyIntegrationTest {

    @ClassRule
    public static ServerRule backendServer = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/trailers", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();

                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
                assertThat(headers.isEndOfStream()).isFalse();

                final HttpHeaders trailers = HttpHeaders.builder()
                                                        .set("armeria-message", "error")
                                                        .endOfStream(true)
                                                        .build();
                assertThat(trailers.isEndOfStream()).isTrue();

                writer.write(headers);
                writer.write(trailers);
                writer.close();

                return writer;
            });

            sb.service("/trailers-only", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();

                final ResponseHeaders trailers = ResponseHeaders.builder(HttpStatus.OK)
                                                                .set("armeria-message", "error")
                                                                .endOfStream(true)
                                                                .build();
                assertThat(trailers.isEndOfStream()).isTrue();

                writer.write(trailers);
                writer.close();

                return writer;
            });

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @ClassRule
    public static ServerRule frontendServer = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/trailers", (ctx, req) -> {
                final WebClient client = WebClient.of(backendServer.httpUri());
                return client.get("/trailers");
            });

            sb.service("/trailers-only", (ctx, req) -> {
                final WebClient client = WebClient.of(backendServer.httpUri());
                return client.get("/trailers-only");
            });

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    public void proxyWithTrailers() throws Throwable {
        final WebClient client = WebClient.of(frontendServer.httpUri());

        final AtomicBoolean headersReceived = new AtomicBoolean();
        final AtomicBoolean complete = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        client.get("/trailers").subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject obj) {
                if (!headersReceived.get()) {
                    headersReceived.set(true);
                    assertThat(obj.isEndOfStream()).isFalse();
                } else {
                    assertThat(obj.isEndOfStream()).isTrue();
                }
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                complete.set(true);
            }

            @Override
            public void onComplete() {
                complete.set(true);
            }
        });

        await().untilTrue(complete);
        final Throwable raisedError = error.get();
        if (raisedError != null) {
            throw raisedError;
        }
    }

    @Test
    public void proxyWithTrailersOnly() throws Throwable {
        final WebClient client = WebClient.of(frontendServer.httpUri());

        final AtomicBoolean complete = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        client.get("/trailers-only").subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject obj) {
                // No data frames.
                assertThat(obj).isInstanceOf(HttpHeaders.class);
                assertThat(obj.isEndOfStream()).isTrue();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                complete.set(true);
            }

            @Override
            public void onComplete() {
                complete.set(true);
            }
        });

        await().untilTrue(complete);
        final Throwable raisedError = error.get();
        if (raisedError != null) {
            throw raisedError;
        }
    }
}
