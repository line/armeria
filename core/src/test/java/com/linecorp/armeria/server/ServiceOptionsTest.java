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

/**
 * The priority of configurations from highest to lowest:
 * 1. ServiceBinderBuilder
 * 2. ServiceOptions (if exists)
 * 3. VirtualHostBuilder
 * 4. ServerBuilder
 */
class ServiceOptionsTest {

    private static final ServiceOptions SERVICE_OPTIONS =
            ServiceOptions.builder().requestTimeoutMillis(5000)
                          .maxRequestLength(1024)
                          .requestAutoAbortDelayMillis(1000)
                          .build();

    @Test
    void serviceOptionsShouldNotOverrideServiceBindingBuilder() {
        final HttpService httpService = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("OK");
            }

            @Override
            public ServiceOptions options() {
                return SERVICE_OPTIONS;
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
    void serviceOptionsShouldOverrideVirtualHostTemplate() {
        final HttpService httpService = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("OK");
            }

            @Override
            public ServiceOptions options() {
                return SERVICE_OPTIONS;
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

            assertThat(sc.requestTimeoutMillis()).isEqualTo(SERVICE_OPTIONS.requestTimeoutMillis());
            assertThat(sc.maxRequestLength()).isEqualTo(SERVICE_OPTIONS.maxRequestLength());
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(
                    SERVICE_OPTIONS.requestAutoAbortDelayMillis());
        }
    }

    @Test
    void serviceOptionsShouldOverrideServerBuilder() {
        final HttpService httpService1 = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("OK");
            }

            @Override
            public ServiceOptions options() {
                return SERVICE_OPTIONS;
            }
        };

        final long defaultRequestTimeoutMillis = 30001;
        final long defaultMaxRequestLength = 30002;
        final long defaultRequestAutoAbortDelayMillis = 30003;
        try (Server server = Server.builder()
                                   .service("/test", httpService1)
                                   .requestTimeoutMillis(defaultRequestTimeoutMillis)
                                   .maxRequestLength(defaultMaxRequestLength)
                                   .requestAutoAbortDelayMillis(defaultRequestAutoAbortDelayMillis)
                                   .build()) {

            final ServiceConfig sc1 = server.serviceConfigs()
                                            .stream()
                                            .filter(s -> s.route().paths().contains("/test"))
                                            .findFirst().get();
            assertThat(sc1.requestTimeoutMillis()).isEqualTo(SERVICE_OPTIONS.requestTimeoutMillis());
            assertThat(sc1.maxRequestLength()).isEqualTo(SERVICE_OPTIONS.maxRequestLength());
            assertThat(sc1.requestAutoAbortDelayMillis()).isEqualTo(
                    SERVICE_OPTIONS.requestAutoAbortDelayMillis());
        }
    }
}
