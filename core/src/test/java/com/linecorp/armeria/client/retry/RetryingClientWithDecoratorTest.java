/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientWithDecoratorTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void responseCauseIsSetWhenExceptionIsRaisedInDecorator() throws InterruptedException {
        final AtomicInteger onExceptionCounter = new AtomicInteger();
        final AtomicInteger logExceptionCounter = new AtomicInteger();
        // Retry only 3 times.
        final RetryRule retryRule = RetryRule.builder()
                                             .onException((ctx, cause) -> {
                                                 assertThat(cause).isInstanceOf(AnticipatedException.class);
                                                 return onExceptionCounter.incrementAndGet() != 3;
                                             })
                                             .onResponseTrailers((ctx, trailers) -> false)
                                             .thenBackoff();
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> delegate.execute(ctx, req)
                                                                    .mapHeaders(headers -> {
                                                                        throw new AnticipatedException();
                                                                    }))
                         .decorator((delegate, ctx, req) -> {
                             ctx.log()
                                .whenAvailable(RequestLogProperty.RESPONSE_CAUSE)
                                .thenAccept(log -> logExceptionCounter.incrementAndGet());
                             return delegate.execute(ctx, req);
                         })
                         .decorator(RetryingClient.builder(retryRule).newDecorator())
                         .build();
        assertThatThrownBy(() -> client.get("/hello").aggregate().join())
                .hasCauseInstanceOf(AnticipatedException.class);
        assertThat(onExceptionCounter.get()).isSameAs(3);
        assertThat(logExceptionCounter.get()).isSameAs(3);
    }
}
