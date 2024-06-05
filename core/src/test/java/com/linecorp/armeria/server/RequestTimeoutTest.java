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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RequestTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    private static class TimeoutNowInServeArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final ImmutableList.Builder<Arguments> builder = ImmutableList.builder();
            for (SessionProtocol protocol : ImmutableList.of(SessionProtocol.H1C, SessionProtocol.H2C)) {
                for (ExchangeType value : ExchangeType.values()) {
                    for (boolean clientSendsOneBody : ImmutableList.of(true, false)) {
                        for (boolean useServiceEventLoop : ImmutableList.of(true, false)) {
                            for (HttpResponse httpResponse : ImmutableList.of(
                                    HttpResponse.of(200),
                                    HttpResponse.delayed(HttpResponse.of(200), Duration.ofMillis(100)),
                                    HttpResponse.streaming())) {
                                builder.add(arguments(protocol, value, clientSendsOneBody,
                                                      useServiceEventLoop, httpResponse));
                            }
                        }
                    }
                }
            }
            return builder.build().stream();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TimeoutNowInServeArgumentsProvider.class)
    void timeoutNowInServe(SessionProtocol protocol, ExchangeType exchangeType,
                           boolean clientSendsOneBody, boolean useServiceEventLoop, HttpResponse httpResponse) {
        server.server().reconfigure(sb -> {
            if (useServiceEventLoop) {
                sb.serviceWorkerGroup(2);
            }
            sb.service("/", new HttpService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    ctx.timeoutNow();
                    return httpResponse;
                }

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return exchangeType;
                }
            });
        });
        final HttpRequestWriter request = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/"));
        if (clientSendsOneBody) {
            // AbstractHttpResponseHandler.scheduleTimeout(); is called before ctx.timeoutNow(); is called.
            request.write(HttpData.ofUtf8("foo"));
        }
        request.close();

        if (!exchangeType.isRequestStreaming() || (!clientSendsOneBody && !useServiceEventLoop)) {
            // The server received the request fully in these cases.
            assertThat(WebClient.of(protocol, server.endpoint(protocol))
                                .execute(request).aggregate().join().status())
                    .isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            // The server did or did not receive the request fully depending on the thread scheduling timing.
            assertThat(WebClient.of(protocol, server.endpoint(protocol))
                                .execute(request).aggregate().join().status())
                    .isIn(HttpStatus.REQUEST_TIMEOUT, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static class TimeoutByTimerArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final ImmutableList.Builder<Arguments> builder = ImmutableList.builder();
            for (SessionProtocol protocol : ImmutableList.of(SessionProtocol.H1C, SessionProtocol.H2C)) {
                for (ExchangeType value : ExchangeType.values()) {
                    for (boolean useServiceEventLoop : ImmutableList.of(true, false)) {
                        for (boolean clientCloseRequest : ImmutableList.of(true, false)) {
                            builder.add(arguments(protocol, value, useServiceEventLoop, clientCloseRequest));
                        }
                    }
                }
            }
            return builder.build().stream();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TimeoutByTimerArgumentsProvider.class)
    void timeoutByTimer(SessionProtocol protocol, ExchangeType exchangeType,
                        boolean useServiceEventLoop, boolean clientCloseRequest) {
        server.server().reconfigure(sb -> {
            sb.requestTimeoutMillis(10);
            if (useServiceEventLoop) {
                sb.serviceWorkerGroup(2);
            }
            sb.service("/", new HttpService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.streaming();
                }

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return exchangeType;
                }
            });
        });
        final HttpRequestWriter request = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/"));
        final HttpStatus expectedStatus;
        if (clientCloseRequest) {
            request.close();
            expectedStatus = HttpStatus.SERVICE_UNAVAILABLE;
        } else {
            expectedStatus = HttpStatus.REQUEST_TIMEOUT;
        }
        assertThat(WebClient.builder(protocol, server.endpoint(protocol))
                            .build()
                            .execute(request).aggregate().join().status())
                .isSameAs(expectedStatus);
    }
}
