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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.common.CancellationScheduler.State;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class HttpPreprocessorTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void invalidSessionProtocol() {
        final WebClient client = WebClient.of(PreClient::execute);
        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(UnprocessedRequestException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctx.sessionProtocol() cannot be 'undefined'");
    }

    @Test
    void overwriteByCustomPreprocessor() {
        final HttpPreprocessor preprocessor =
                HttpPreprocessor.of(SessionProtocol.HTTP, Endpoint.of("127.0.0.1"),
                                    eventLoop.get());
        final WebClient client = WebClient.builder()
                                          .preprocessor(preprocessor)
                                          .decorator((delegate, ctx, req) -> HttpResponse.of(200))
                                          .build();
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.get("https://127.0.0.2").aggregate().join();
            assertThat(res.status().code()).isEqualTo(200);
            ctx = captor.get();
        }
        assertThat(ctx.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(ctx.authority()).isEqualTo("127.0.0.1");
        assertThat(ctx.eventLoop().withoutContext()).isSameAs(eventLoop.get());
    }

    @Test
    void preprocessorOrder() {
        final List<String> list = new ArrayList<>();
        final HttpPreprocessor p1 = RunnablePreprocessor.of(() -> list.add("1"));
        final HttpPreprocessor p2 = RunnablePreprocessor.of(() -> list.add("2"));
        final HttpPreprocessor p3 = RunnablePreprocessor.of(() -> list.add("3"));

        final WebClient client = WebClient.builder()
                                          .preprocessor(p1)
                                          .preprocessor(p2)
                                          .preprocessor(p3)
                                          .decorator((delegate, ctx, req) -> HttpResponse.of(200))
                                          .build();
        final AggregatedHttpResponse res = client.get("http://127.0.0.1").aggregate().join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(list).containsExactly("3", "2", "1");
    }

    @Test
    void cancellationSchedulerIsInitializedCorrectly() {
        final HttpPreprocessor preprocessor = (delegate, ctx, req) -> {
            ctx.setEventLoop(eventLoop.get());
            return delegate.execute(ctx, req);
        };
        final BlockingWebClient client =
                WebClient.builder("http://1.2.3.4")
                         .preprocessor(preprocessor)
                         .responseTimeoutMode(ResponseTimeoutMode.FROM_START)
                         .responseTimeoutMillis(10_000)
                         .decorator((delegate, ctx, req) -> {
                             assertThat(ctx.as(ClientRequestContextExtension.class)
                                           .responseCancellationScheduler()
                                           .state())
                                     .isEqualTo(State.SCHEDULED);
                             return HttpResponse.of(200);
                         })
                         .build()
                         .blocking();
        assertThat(client.get("/").status().code()).isEqualTo(200);
    }

    private static final class RunnablePreprocessor implements HttpPreprocessor {

        private static HttpPreprocessor of(Runnable runnable) {
            return new RunnablePreprocessor(runnable);
        }

        private final Runnable runnable;

        private RunnablePreprocessor(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public HttpResponse execute(PreClient<HttpRequest, HttpResponse> delegate,
                                    PreClientRequestContext ctx, HttpRequest req) throws Exception {
            runnable.run();
            return delegate.execute(ctx, req);
        }
    }
}
