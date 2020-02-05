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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpClientResponseTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(0);
            sb.service("/no-timeout", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @Test
    void setRequestTimeoutAtPastTimeClient() {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    ctx.eventLoop().schedule(() -> ctx.setResponseTimeoutAt(Instant.now().minusSeconds(1)),
                                             1, TimeUnit.SECONDS);
                    return delegate.execute(ctx, req);
                })
                .build();
        assertThatThrownBy(() -> client.get(server.httpUri() + "/no-timeout").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
    }

    @ParameterizedTest
    @ArgumentsSource(TimeoutDecoratorSource.class)
    void setRequestTimeoutAtPendingTimeoutTask(Consumer<? super ClientRequestContext> timeoutCustomizer) {
        final WebClient client = WebClient
                .builder(server.httpUri())
                .option(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(30L))
                .decorator((delegate, ctx, req) -> {
                    // set timeout before initializing timeout controller
                    timeoutCustomizer.accept(ctx);
                    return delegate.execute(ctx, req);
                })
                .build();
        await().timeout(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThatThrownBy(() -> client.get(server.httpUri() + "/no-timeout")
                                           .aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
        });
    }

    private static class TimeoutDecoratorSource implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext)
                throws Exception {
            final Stream<Consumer<? super ClientRequestContext>> timeoutCustomizers = Stream.of(
                    ctx -> ctx.setResponseTimeoutAt(Instant.now().minusSeconds(1)),
                    ctx -> ctx.setResponseTimeoutAfterMillis(1000),
                    ctx -> ctx.setResponseTimeoutMillis(1000)
            );
            return timeoutCustomizers.map(Arguments::of);
        }
    }
}
