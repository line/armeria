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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.Channel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpResponseDecoderTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoderTest.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, Armeria!"));
        }
    };

    /**
     * This test would be passed because the {@code cancelAction} method of the
     * {@link HttpResponseWrapper} is invoked in the event loop of the {@link Channel}.
     */
    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void confirmResponseStartAndEndInTheSameThread(SessionProtocol protocol)
            throws InterruptedException {
        final AtomicBoolean failed = new AtomicBoolean();
        final RetryRule strategy = (ctx, cause) ->
                UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.withoutDelay()));

        final WebClientBuilder builder = WebClient.builder(server.uri(protocol));
        // In order to use a different thread to subscribe to the response.
        builder.decorator(RetryingClient.builder(strategy)
                                        .maxTotalAttempts(2)
                                        .newDecorator());
        builder.decorator((delegate, ctx, req) -> {
            final AtomicReference<Thread> responseStartedThread = new AtomicReference<>();
            ctx.log().whenAvailable(RequestLogProperty.RESPONSE_START_TIME).thenAccept(log -> {
                responseStartedThread.set(Thread.currentThread());
            });
            ctx.log().whenComplete().thenAccept(log -> {
                final Thread thread = responseStartedThread.get();
                if (thread != null && thread != Thread.currentThread()) {
                    logger.error("{} Response ended in another thread: {} != {}",
                                 ctx, thread, Thread.currentThread(), new RuntimeException());
                    failed.set(true);
                }
            });
            return delegate.execute(ctx, req);
        });

        // Execute it as much as we can in order to confirm that there's no problem.
        final WebClient client = builder.build();
        final int n = 1000;
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            client.execute(HttpRequest.of(HttpMethod.GET, "/")).aggregate()
                  .handle((unused1, unused2) -> {
                      latch.countDown();
                      return null;
                  });
        }

        latch.await(System.getenv("CI") != null ? 60 : 10, TimeUnit.SECONDS);
        assertThat(failed.get()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void responseCompleteNormallyIfRequestIsAborted(SessionProtocol protocol) throws Exception {
        final WebClient client = WebClient.of(server.uri(protocol));
        final HttpRequestWriter request = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/"));
        final AggregatedHttpResponse res = client.execute(request).aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Hello, Armeria!");
        // The stream is aborted with ResponseCompleteException
        // in HttpResponseDecoder.close(...) after the client receives the response.
        request.whenComplete().handle((unused, cause) -> {
            assertThat(cause).isExactlyInstanceOf(ResponseCompleteException.class);
            return null;
        }).join();
    }
}
