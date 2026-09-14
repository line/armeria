/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Verifies that {@link RetryingClient} releases the duplicated request's pooled content
 * when a decorator short-circuits without consuming the request body.
 */
class RetryingClientRequestLeakTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
        }
    };

    @Test
    void shouldReleaseRequestBodyWhenDecoratorShortCircuits() {
        final AtomicInteger attemptCount = new AtomicInteger();
        final ByteBuf sentinel = ByteBufAllocator.DEFAULT.buffer().writeBytes("test-body".getBytes());
        assertThat(sentinel.refCnt()).isEqualTo(1);

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             attemptCount.incrementAndGet();
                             return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                         })
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder()
                                          .onServerErrorStatus()
                                          .thenBackoff(Backoff.withoutDelay()), 3))
                         .build();

        final AggregatedHttpResponse res = client.post("/", HttpData.wrap(sentinel)).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(attemptCount.get()).isEqualTo(3);

        // The sentinel ByteBuf should have been fully released.
        assertThat(sentinel.refCnt()).isZero();
    }
}
