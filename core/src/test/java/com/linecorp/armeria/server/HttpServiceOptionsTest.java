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
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.HttpServiceOption;

/**
 * The priority of configurations from highest to lowest:
 * 1. ServiceBinderBuilder
 * 2. HttpServiceOptions (if exists)
 * 3. VirtualHostBuilder
 * 4. ServerBuilder
 */
class HttpServiceOptionsTest {
    private final HttpServiceOptions httpServiceOptions =
            HttpServiceOptions.builder().requestTimeoutMillis(100001).maxRequestLength(10002)
                              .requestAutoAbortDelayMillis(10003).build();

    @Test
    void httpServiceOptionsShouldNotOverrideServiceBindingBuilder() {
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
    void httpServiceOptionsShouldOverrideVirtualHostTemplate() {
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
        try (Server server = Server.builder()
                                   .virtualHost("example.com")
                                   .requestTimeoutMillis(20001)
                                   .maxRequestLength(20002)
                                   .requestAutoAbortDelayMillis(20003)
                                   .service("/test", httpService)
                                   .and()
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/test"))
                                           .findFirst().get();

            assertThat(sc.requestTimeoutMillis()).isEqualTo(httpServiceOptions.requestTimeoutMillis());
            assertThat(sc.maxRequestLength()).isEqualTo(httpServiceOptions.maxRequestLength());
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(
                    httpServiceOptions.requestAutoAbortDelayMillis());
        }
    }

    @Test
    void httpServiceOptionsShouldOverrideServerBuilder() {
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

        final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30001;
        final long DEFAULT_MAX_REQUEST_LENGTH = 30002;
        final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS = 30003;
        try (Server server = Server.builder()
                                   .service("/test", httpService1)
                                   .requestTimeoutMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS)
                                   .maxRequestLength(DEFAULT_MAX_REQUEST_LENGTH)
                                   .requestAutoAbortDelayMillis(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS)
                                   .build()) {

            final ServiceConfig sc1 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test"))
                                            .findFirst().get();
            assertThat(sc1.requestTimeoutMillis()).isEqualTo(httpServiceOptions.requestTimeoutMillis());
            assertThat(sc1.maxRequestLength()).isEqualTo(httpServiceOptions.maxRequestLength());
            assertThat(sc1.requestAutoAbortDelayMillis()).isEqualTo(
                    httpServiceOptions.requestAutoAbortDelayMillis());
        }
    }

    @Test
    void httpServiceOptionAnnotationShouldBeAppliedWhenConfigured() {
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
}
