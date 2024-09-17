/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutMode;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryTimeoutCancellationTest {

    private static AtomicInteger counter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(unused -> {
                    if (counter.getAndIncrement() < 2) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        return HttpResponse.of("hello");
                    }
                }));
            });
        }
    };

    @BeforeEach
    void setUp() {
        counter.set(0);
    }

    @Test
    void shouldCancelTimoutScheduler() {
        final Function<? super HttpClient, RetryingClient> retryingClient =
                RetryingClient.builder(RetryRule.builder()
                                                .onServerErrorStatus()
                                                .thenBackoff(Backoff.fixed(100)))
                              .maxTotalAttempts(3)
                              .responseTimeoutMillisForEachAttempt(30_000)
                              .newDecorator();
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                  .decorator(retryingClient)
                                                  .responseTimeoutMode(ResponseTimeoutMode.FROM_START)
                                                  .responseTimeoutMillis(80_000)
                                                  .build()
                                                  .blocking();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.post("/foo", "hello");
            final ClientRequestContext ctx = captor.get();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("hello");
            assertTimeoutNotScheduled(ctx);
            for (RequestLogAccess child : ctx.log().children()) {
                assertTimeoutNotScheduled((ClientRequestContext) child.whenComplete().join().context());
            }
        }
    }

    private static void assertTimeoutNotScheduled(ClientRequestContext ctx) {
        final CancellationScheduler scheduler = ctx.as(ClientRequestContextExtension.class)
                                                   .responseCancellationScheduler();
        assertThat(scheduler.isScheduled()).isFalse();
    }
}
