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
        assertThatThrownBy(() -> RetryStrategy.builder().route().methods(HttpMethod.HEAD).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one strategy");

        final RetryStrategy strategy = RetryStrategy.builder().route()
                                                    .methods(HttpMethod.HEAD)
                                                    .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                                    .build()
                                                    .build();

        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.HEAD, "/"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFutureValue(strategy.shouldRetry(ctx, null)).isNotNull();
    }

    @Test
    void buildFluently() {
        final Backoff idempotentBackoff = Backoff.fixed(100);
        final Backoff unprocessedBackoff = Backoff.fixed(200);
        final RetryStrategy strategy = RetryStrategy.builder()
                                                    .route().idempotentMethods()
                                                    .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                                    .onException(ClosedChannelException.class)
                                                    .onStatusClass(HttpStatusClass.CLIENT_ERROR)
                                                    .build(idempotentBackoff)
                                                    .onUnProcessed(unprocessedBackoff)
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
}
