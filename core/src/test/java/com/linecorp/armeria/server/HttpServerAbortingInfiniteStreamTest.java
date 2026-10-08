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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@TestInstance(Lifecycle.PER_METHOD)
class HttpServerAbortingInfiniteStreamTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerAbortingInfiniteStreamTest.class);

    private final CompletableFuture<HttpResponseWriter> serverResponse = new CompletableFuture<>();

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        public void after(ExtensionContext context) throws Exception {
            stop().get(10, TimeUnit.SECONDS);
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/infinity", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();
                writer.write(ResponseHeaders.of(HttpStatus.OK));

                // Do not close the response writer because it returns data infinitely.
                writer.whenConsumed().thenRun(new Runnable() {
                    @Override
                    public void run() {
                        writer.write(HttpData.ofUtf8("infinite stream"));
                        writer.whenConsumed().thenRun(this);
                    }
                });
                serverResponse.complete(writer);
                return writer;
            });
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void shouldCancelInfiniteStreamImmediately(SessionProtocol protocol) throws Exception {
        try (ClientFactory factory = ClientFactory.builder().build()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(factory).build();
            final HttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/infinity"));

            final CompletableFuture<Void> cancellationRequested = new CompletableFuture<>();
            response.subscribe(new Subscriber<HttpObject>() {
                @Nullable
                private Subscription subscription;
                private int count;

                @Override
                public void onSubscribe(Subscription s) {
                    subscription = s;
                    s.request(1);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    assertThat(subscription).isNotNull();
                    if (++count == 10) {
                        logger.debug("Cancel subscription: count={}", count);
                        subscription.cancel();
                        cancellationRequested.complete(null);
                        return;
                    }
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable t) {
                    cancellationRequested.completeExceptionally(t);
                }

                @Override
                public void onComplete() {
                    cancellationRequested.completeExceptionally(
                            new IllegalStateException("Infinite response completed before cancellation"));
                }
            });

            cancellationRequested.get(10, TimeUnit.SECONDS);
            final HttpResponseWriter writer = serverResponse.get(10, TimeUnit.SECONDS);
            assertThat(server.requestContextCaptor().take().sessionProtocol()).isEqualTo(protocol);
            assertThatThrownBy(() -> writer.whenComplete().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CancelledSubscriptionException.class);
        }
    }
}
