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

package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceOption;

public class ServiceOptionTest {
    @Test
    void serviceOptionAnnotationShouldBeAppliedWhenConfiguredAtMethodLevel() {
        final class TestAnnotatedService {
            @ServiceOption(
                    requestTimeoutMillis = 11111,
                    maxRequestLength = 1111
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
            assertThat(sc1.requestAutoAbortDelayMillis()).isEqualTo(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);

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
    void serviceOptionAnnotationShouldBeAppliedWhenConfiguredAtClassLevel() {
        @ServiceOption(
                requestTimeoutMillis = 11111,
                maxRequestLength = 1111
        )
        final class TestAnnotatedService {
            @Get("/test")
            public HttpResponse test() {
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
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/test"))
                                           .findFirst().orElse(null);

            assertThat(sc).isNotNull();
            assertThat(sc.requestTimeoutMillis()).isEqualTo(11111);
            assertThat(sc.maxRequestLength()).isEqualTo(1111);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);
        }
    }

    @Test
    void serviceOptionAnnotationAtMethodLevelShouldOverrideServiceOptionAtClassLevel() {
        @ServiceOption(
                requestTimeoutMillis = 11111,
                maxRequestLength = 1111,
                requestAutoAbortDelayMillis = 111
        )
        final class TestAnnotatedService {
            @ServiceOption(
                    requestTimeoutMillis = 22222,
                    maxRequestLength = 2222,
                    requestAutoAbortDelayMillis = 222
            )
            @Get("/test")
            public HttpResponse test() {
                return HttpResponse.of("OK");
            }
        }

        try (Server server = Server.builder()
                                   .annotatedService(new TestAnnotatedService())
                                   .build()) {
            final ServiceConfig sc = server.serviceConfigs()
                                           .stream()
                                           .filter(s -> s.route().paths().contains("/test"))
                                           .findFirst().orElse(null);

            assertThat(sc).isNotNull();
            assertThat(sc.requestTimeoutMillis()).isEqualTo(22222);
            assertThat(sc.maxRequestLength()).isEqualTo(2222);
            assertThat(sc.requestAutoAbortDelayMillis()).isEqualTo(222);
        }
    }
}
