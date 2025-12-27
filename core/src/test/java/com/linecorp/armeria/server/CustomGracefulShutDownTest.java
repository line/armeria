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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.GracefulShutdown;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ShuttingDownException;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class CustomGracefulShutDownTest {

    @ArgumentsSource(GracefulShutdownProvider.class)
    @ParameterizedTest
    void testGracefulShutdown(GracefulShutdown gracefulShutdown, Class<Throwable> expectedCause,
                              HttpStatus expectedStatus) {
        final CompletableFuture<ServiceRequestContext> whenReceived = new CompletableFuture<>();
        final ServerBuilder serverBuilder = Server
                .builder()
                .service("/", (ctx, req) -> {
                    whenReceived.complete(ctx);
                    return HttpResponse.streaming();
                })
                .gracefulShutdown(gracefulShutdown)
                .errorHandler((ctx, cause) -> {
                    if (cause instanceof AnticipatedException) {
                        return HttpResponse.of(HttpStatus.BAD_GATEWAY);
                    }
                    return null;
                });
        if (AnticipatedException.class.isAssignableFrom(expectedCause)) {
            serverBuilder.gracefulShutdownExceptionFactory(
                    (ctx, req) -> new AnticipatedException()
            );
        }
        final Server server = serverBuilder.build();
        server.start().join();
        final WebClient client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                                          .responseTimeoutMillis(0)
                                          .decorator(LoggingClient.newDecorator())
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> res = client.get("/").aggregate();
        final ServiceRequestContext sctx = whenReceived.join();
        final CompletableFuture<Void> closeFuture = server.stop();
        final AggregatedHttpResponse response = res.join();
        assertThat(response.status()).isEqualTo(expectedStatus);
        assertThat(sctx.log().whenComplete().join().responseCause()).isInstanceOf(expectedCause);
        closeFuture.join();
    }

    private static class GracefulShutdownProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            final GracefulShutdown customError =
                    GracefulShutdown.builder()
                                    .quietPeriod(Duration.ofMillis(500))
                                    .timeout(Duration.ofMillis(500))
                                    .build();

            final GracefulShutdown defaultError =
                    GracefulShutdown.builder()
                                    .quietPeriod(Duration.ofMillis(200))
                                    .timeout(Duration.ofMillis(200))
                                    .build();
            return Stream.of(
                    Arguments.of(defaultError, ShuttingDownException.class, HttpStatus.SERVICE_UNAVAILABLE),
                    Arguments.of(customError, AnticipatedException.class, HttpStatus.BAD_GATEWAY));
        }
    }
}
