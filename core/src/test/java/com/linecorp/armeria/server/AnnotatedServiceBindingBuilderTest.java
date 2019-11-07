/*
 * Copyright 2019 LINE Corporation
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

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class AnnotatedServiceBindingBuilderTest {

    private static final ExceptionHandlerFunction handlerFunction = (ctx, req, cause) -> HttpResponse.of(501);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService()
              .exceptionHandler(handlerFunction)
              .build(new TestService());
        }
    };

    @Test
    void testWhenPathPrefixIsNotGivenThenUsesDefault() {
        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(Duration.ofMillis(5000))
                                    .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).allMatch("/foo"::equals);
    }

    @Test
    void testWhenPathPrefixIsGivenThenItIsPrefixed() {
        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(Duration.ofMillis(5000))
                                    .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                    .pathPrefix("/home")
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).allMatch("/home/foo"::equals);
    }

    @Test
    void testAllConfigurationsAreRespected() {
        final boolean verboseResponse = true;
        final boolean shutdownOnStop = true;
        final long maxRequestLength = 2 * 1024;
        final AccessLogWriter accessLogWriter = AccessLogWriter.common();
        final Duration requestTimeoutDuration = Duration.ofMillis(1000);
        final ContentPreviewerFactory factory = (ctx, headers) -> ContentPreviewer.ofText(1024);

        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(requestTimeoutDuration)
                                    .maxRequestLength(maxRequestLength)
                                    .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                    .pathPrefix("/home")
                                    .accessLogWriter(accessLogWriter, shutdownOnStop)
                                    .contentPreviewerFactory(factory)
                                    .verboseResponses(verboseResponse)
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(serviceConfig.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(serviceConfig.requestContentPreviewerFactory()).isEqualTo(factory);
        assertThat(serviceConfig.verboseResponses()).isTrue();
    }

    @Test
    void testServiceDecoration_shouldCatchException() throws IOException {
        final HttpStatus status = get("/foo").status();

        assertThat(status.code()).isEqualTo(501);
    }

    private static AggregatedHttpResponse get(String path) {
        final HttpClient httpClient = HttpClient.of(server.httpUri("/"));
        return httpClient.get(path).aggregate().join();
    }

    private static class TestService {
        @Get("/foo")
        public HttpResponse foo() {
            throw new RuntimeException();
        }
    }
}
