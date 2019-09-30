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
package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.Unpooled;

class CircuitBreakerHttpClientIntegrationTest {
    @Test
    void abortOnFailFast() throws Exception {
        final AtomicLong tickerValue = new AtomicLong();
        final CircuitBreaker circuitBreaker = CircuitBreaker.builder()
                                                            .ticker(tickerValue::get)
                                                            .counterUpdateInterval(Duration.ofSeconds(1))
                                                            .minimumRequestThreshold(0)
                                                            .build();

        final HttpClient client = new HttpClientBuilder()
                .decorator(CircuitBreakerHttpClient.newDecorator(
                        circuitBreaker,
                        (ctx, cause) -> CompletableFuture.completedFuture(false)))
                .build();

        for (int i = 0; i < 3; i++) {
            final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "h2c://127.0.0.1:1");
            final ByteBufHttpData data = new ByteBufHttpData(Unpooled.wrappedBuffer(new byte[] { 0 }), true);
            req.write(data);

            switch (i) {
                case 0:
                case 1:
                    assertThat(circuitBreaker.canRequest()).isTrue();
                    assertThatThrownBy(() -> client.execute(req).aggregate().join())
                            .isInstanceOfSatisfying(CompletionException.class, cause -> {
                                assertThat(cause.getCause()).isInstanceOf(UnprocessedRequestException.class)
                                                            .hasCauseInstanceOf(ConnectException.class);
                            });
                    break;
                default:
                    await().until(() -> !circuitBreaker.canRequest());
                    assertThatThrownBy(() -> client.execute(req).aggregate().join())
                            .isInstanceOf(CompletionException.class)
                            .hasCauseInstanceOf(FailFastException.class);
            }

            await().untilAsserted(() -> {
                assertThat(req.completionFuture()).hasFailedWithThrowableThat()
                                                  .isInstanceOf(AbortedStreamException.class);
            });

            assertThat(data.refCnt()).isZero();

            tickerValue.addAndGet(TimeUnit.SECONDS.toNanos(1));
        }
    }
}
