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

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class VirtualHostAnnotatedServiceBindingBuilderTest {

    private static final String TEST_HOST = "foo.com";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.virtualHost(TEST_HOST)
              .annotatedService()
              .pathPrefix("/foo")
              .exceptionHandler((ctx, req, cause) -> HttpResponse.of(501))
              .build(new TestService())
              .annotatedService()
              .pathPrefix("/custom")
              .configuratorCustomizer(
                      setters -> setters.configureExceptionHandlers((ctx, req, cause) -> HttpResponse.of(502)))
              .build(new TestService());
        }
    };

    @Test
    void testAllConfigsAreSet() {
        final boolean verboseResponse = true;
        final boolean shutdownOnStop = true;
        final long maxRequestLength = 2 * 1024;
        final AccessLogWriter accessLogWriter = AccessLogWriter.common();
        final Duration requestTimeoutDuration = Duration.ofMillis(1000);
        final ContentPreviewerFactory factory = (ctx, headers) -> ContentPreviewer.ofText(1024);

        final VirtualHost virtualHost = new VirtualHostBuilder(Server.builder(), false)
                .annotatedService()
                .requestTimeout(requestTimeoutDuration)
                .maxRequestLength(maxRequestLength)
                .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                .pathPrefix("/foo")
                .accessLogWriter(accessLogWriter, shutdownOnStop)
                .contentPreviewerFactory(factory)
                .verboseResponses(verboseResponse)
                .build(new TestService())
                .build();

        assertThat(virtualHost.serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = virtualHost.serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).allMatch("/foo/bar"::equals);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(serviceConfig.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(serviceConfig.requestContentPreviewerFactory()).isEqualTo(factory);
        assertThat(serviceConfig.verboseResponses()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "/foo/bar, 501",
            "/custom/bar, 502"
    })
    void testServiceDecoration_shouldCatchException(String path, Integer statusCode) throws Exception {
        final AggregatedHttpResponse join = get(path);

        assertThat(join.status().code()).isEqualTo(statusCode);
    }

    private AggregatedHttpResponse get(String path) {
        final Endpoint endpoint = Endpoint.of(TEST_HOST, server.httpPort()).withIpAddr("127.0.0.1");
        final HttpClient httpClientTest = HttpClient.of(SessionProtocol.HTTP, endpoint);
        return httpClientTest.get(path).aggregate().join();
    }

    private static class TestService {
        @Get("/bar")
        public HttpResponse foo() {
            throw new RuntimeException();
        }
    }
}
