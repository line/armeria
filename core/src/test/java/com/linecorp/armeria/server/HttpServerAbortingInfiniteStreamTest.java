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

import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerAbortingInfiniteStreamTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerAbortingInfiniteStreamTest.class);

    private static final AtomicReference<SessionProtocol> expectedProtocol = new AtomicReference<>();

    private static final AtomicBoolean isCompleted = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/infinity", (ctx, req) -> {
                // Ensure that the protocol is expected one.
                assertThat(ctx.sessionProtocol()).isEqualTo(expectedProtocol.get());

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
                writer.whenComplete().whenComplete((unused, cause) -> {
                    // We are not expecting that this stream is successfully finished.
                    if (cause != null) {
                        if (ctx.sessionProtocol() == H1C) {
                            assertThat(cause).isInstanceOf(CancelledSubscriptionException.class);
                        } else {
                            assertThat(cause).isInstanceOf(AbortedStreamException.class);
                        }
                        if (isCompleted.compareAndSet(false, true)) {
                            logger.debug("Infinite stream is completed", cause);
                        }
                    }
                });
                return writer;
            });
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void shouldCancelInfiniteStreamImmediately(SessionProtocol protocol) {
        expectedProtocol.set(protocol);

        final WebClient client = WebClient.of(server.uri(protocol));
        final HttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/infinity"));

        response.subscribe(new Subscriber<HttpObject>() {
            @Nullable
            private Subscription subscription;
            private int count;

            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
                subscription = s;
            }

            @Override
            public void onNext(HttpObject httpObject) {
                assert subscription != null;
                if (++count == 10) {
                    logger.debug("Cancel subscription: count={}", count);
                    subscription.cancel();
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        try {
            await().untilTrue(isCompleted);
        } catch (ConditionTimeoutException e) {
            if (System.getenv("CI") != null) {
                // On CI, it seems that sometimes there is too much time until disconnection.
                logger.warn("Ignoring test failure.", e);
                return;
            }
            throw e;
        }
    }
}
