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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.internal.common.websocket.WebSocketUtil;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.HttpServiceOption;
import com.linecorp.armeria.server.websocket.WebSocketService;

class HttpServiceOptionsTest {
    private final HttpServiceOptions httpServiceOptions = HttpServiceOptions.builder()
                                                                            .requestTimeoutMillis(100001)
                                                                            .maxRequestLength(10002)
                                                                            .requestAutoAbortDelayMillis(10003)
                                                                            .build();

    @Test
    void httpServiceOptionsShouldOverrideDefaultValues() {
        final HttpService httpService1 = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("OK");
            }

            @Override
            public HttpServiceOptions options() {
                return httpServiceOptions;
            }
        };
        final HttpService httpService2 = (ctx, req) -> HttpResponse.of("OK");

        final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30001;
        final long DEFAULT_MAX_REQUEST_LENGTH = 30002;
        final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS = 30003;
        try (Server server = Server.builder()
                                   .service("/test1", httpService1)
                                   .service("/test2", httpService2)
                                   .requestTimeoutMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS)
                                   .maxRequestLength(DEFAULT_MAX_REQUEST_LENGTH)
                                   .requestAutoAbortDelayMillis(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS)
                                   .build()) {

            final ServiceConfig sc1 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test1"))
                                            .findFirst().get();
            assertThat(sc1.requestTimeoutMillis()).isEqualTo(httpServiceOptions.requestTimeoutMillis());
            assertThat(sc1.maxRequestLength()).isEqualTo(httpServiceOptions.maxRequestLength());
            assertThat(sc1.requestAutoAbortDelayMillis()).isEqualTo(
                    httpServiceOptions.requestAutoAbortDelayMillis());

            final ServiceConfig sc2 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test2"))
                                            .findFirst().get();
            assertThat(sc2.requestTimeoutMillis()).isEqualTo(DEFAULT_REQUEST_TIMEOUT_MILLIS);
            assertThat(sc2.maxRequestLength()).isEqualTo(DEFAULT_MAX_REQUEST_LENGTH);
            assertThat(sc2.requestAutoAbortDelayMillis()).isEqualTo(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);
        }
    }

    @Test
    void httpServiceOptionAnnotationShouldOverrideDefaultValues() {
        final class TestAnnotatedService {

            @HttpServiceOption(
                    requestTimeoutMillis = 11111,
                    maxRequestLength = 1111,
                    requestAutoAbortDelayMillis = 111
            )
            @Get("/test1")
            public HttpResponse test1() {
                return HttpResponse.of("OK");
            }

            @Get("/test2")
            public HttpResponse test2() {
                return HttpResponse.of("OK");
            }
        }

        final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30001;
        final long DEFAULT_MAX_REQUEST_LENGTH = 30002;
        final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS = 30003;

        try (Server server = Server.builder().annotatedService(new TestAnnotatedService())
                                   .requestTimeoutMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS)
                                   .maxRequestLength(DEFAULT_MAX_REQUEST_LENGTH)
                                   .requestAutoAbortDelayMillis(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS)
                                   .build()) {
            final ServiceConfig sc1 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test1"))
                                            .findFirst().orElse(null);

            assertThat(sc1).isNotNull();
            assertThat(sc1.requestTimeoutMillis()).isEqualTo(11111);
            assertThat(sc1.maxRequestLength()).isEqualTo(1111);
            assertThat(sc1.requestAutoAbortDelayMillis()).isEqualTo(111);

            // default values should be applied
            final ServiceConfig sc2 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test2"))
                                            .findFirst().get();
            assertThat(sc2.requestTimeoutMillis()).isEqualTo(DEFAULT_REQUEST_TIMEOUT_MILLIS);
            assertThat(sc2.maxRequestLength()).isEqualTo(DEFAULT_MAX_REQUEST_LENGTH);
            assertThat(sc2.requestAutoAbortDelayMillis()).isEqualTo(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);
        }
    }

    @Test
    void settersShouldOverrideHttpServiceOptions() {
        final HttpService httpService = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("OK");
            }

            @Override
            public HttpServiceOptions options() {
                return httpServiceOptions;
            }
        };

        try (Server server = Server.builder().route().path("/test")
                                   .requestTimeoutMillis(20001)
                                   .maxRequestLength(20002)
                                   .requestAutoAbortDelayMillis(20003)
                                   .build(httpService)
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs().get(0);

            assertThat(sc.requestTimeoutMillis()).isEqualTo(20001);
            assertThat(sc.maxRequestLength()).isEqualTo(20002);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(20003);
        }
    }

    @Test
    void webSocketServiceHttpServiceOptionsTest() {
        final WebSocketService webSocketService = WebSocketService.of((ctx, in) -> WebSocket.streaming());
        try (Server server = Server.builder()
                                   .route()
                                   .path("/")
                                   .build(webSocketService)
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/"))
                                           .findFirst().orElse(null);

            // Specific configuration for websocket service should be applied by default
            assertThat(sc).isNotNull();
            assertThat(sc.requestTimeoutMillis()).isEqualTo(
                    WebSocketUtil.DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS);
            assertThat(sc.maxRequestLength()).isEqualTo(WebSocketUtil.DEFAULT_MAX_REQUEST_RESPONSE_LENGTH);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(
                    WebSocketUtil.DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);
        }

        try (Server server = Server.builder()
                                   .route().path("/")
                                   .requestTimeoutMillis(40001)
                                   .maxRequestLength(40002)
                                   .requestAutoAbortDelayMillis(40003)
                                   .build(webSocketService)
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/"))
                                           .findFirst().orElse(null);

            // Setters should override web socket default values
            assertThat(sc).isNotNull();
            assertThat(sc.requestTimeoutMillis()).isEqualTo(40001);
            assertThat(sc.maxRequestLength()).isEqualTo(40002);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(40003);
        }

        try (Server server = Server.builder()
                                   .requestTimeoutMillis(50001)
                                   .maxRequestLength(50002)
                                   .requestAutoAbortDelayMillis(50003)
                                   .service("/", webSocketService)
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/"))
                                           .findFirst().orElse(null);

            // Web socket default values should override default values configured in the server builder
            assertThat(sc).isNotNull();
            assertThat(sc.requestTimeoutMillis()).isEqualTo(
                    WebSocketUtil.DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS);
            assertThat(sc.maxRequestLength()).isEqualTo(WebSocketUtil.DEFAULT_MAX_REQUEST_RESPONSE_LENGTH);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(
                    WebSocketUtil.DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);
        }
    }
}
