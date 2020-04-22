/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.client.retry.RetryStrategyBuilderTest.assertFutureValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.ClosedChannelException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;

class RetryStrategyBindingBuilderTest {
    @Test
    void methodFilter() {
        assertThatThrownBy(() -> RetryStrategy.builder().onMethods(HttpMethod.HEAD).thenDefaultBackoff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one");

        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onMethods(HttpMethod.HEAD)
                             .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                             .thenDefaultBackoff()
                             .build();

        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.HEAD, "/"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFutureValue(strategy.shouldRetry(ctx, null)).isNotNull();
    }

    @Test
    void buildFluently() {
        final Backoff idempotentBackoff = Backoff.fixed(100);
        final Backoff unprocessedBackoff = Backoff.fixed(200);
        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onIdempotentMethods()
                             .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                             .onException(ClosedChannelException.class)
                             .onStatusClass(HttpStatusClass.CLIENT_ERROR)
                             .thenBackoff(idempotentBackoff)
                             .onUnProcessed()
                             .thenBackoff(unprocessedBackoff)
                             .build();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));

        assertFutureValue(strategy.shouldRetry(ctx1, null)).isSameAs(idempotentBackoff);

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFutureValue(strategy.shouldRetry(ctx2, null)).isNull();

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.PUT, "/"));
        assertFutureValue(strategy.shouldRetry(ctx3, new ClosedChannelException())).isSameAs(idempotentBackoff);

        final ClientRequestContext ctx4 = ClientRequestContext.of(HttpRequest.of(HttpMethod.PUT, "/"));
        assertFutureValue(strategy.shouldRetry(ctx4,
                                               new UnprocessedRequestException(new ClosedChannelException())))
                .isSameAs(unprocessedBackoff);
    }

    @Test
    void noRetry() {
        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onMethods(HttpMethod.POST).thenStop()
                             .onException().thenDefaultBackoff()
                             .build();

        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertFutureValue(strategy.shouldRetry(ctx1, new RuntimeException())).isSameAs(Backoff.ofDefault());

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        assertFutureValue(strategy.shouldRetry(ctx2, new RuntimeException())).isNotNull();
    }

    @Test
    void noDelay() {
        final int maxAttempts = 10;
        final RetryStrategy strategy =
                RetryStrategy.builder()
                             .onStatus(status -> status == HttpStatus.BAD_REQUEST ||
                                                 status == HttpStatus.TOO_MANY_REQUESTS)
                             .thenImmediately(maxAttempts)
                             .build();

        for (int i = 1; i < maxAttempts; i++) {
            final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
            assertThat(strategy.shouldRetry(ctx1, null).toCompletableFuture().join()
                               .nextDelayMillis(i))
                    .isEqualTo(0);

            final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
            assertFutureValue(strategy.shouldRetry(ctx2, null)).isNull();
        }

        final ClientRequestContext ctx3 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx3.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.BAD_REQUEST));
        assertThat(strategy.shouldRetry(ctx3, null).toCompletableFuture().join()
                           .nextDelayMillis(maxAttempts + 1))
                .isEqualTo(-1);
    }
}
