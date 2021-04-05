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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceBindingBuilderTest {

    private static final ExceptionHandlerFunction handlerFunction = (ctx, req, cause) -> HttpResponse.of(501);
    private static final JacksonResponseConverterFunction customJacksonResponseConverterFunction =
            customJacksonResponseConverterFunction();

    private static JacksonResponseConverterFunction customJacksonResponseConverterFunction() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return new JacksonResponseConverterFunction(objectMapper);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedServiceExtensions(ImmutableList.of(),
                                          ImmutableList.of(customJacksonResponseConverterFunction),
                                          ImmutableList.of())
              .annotatedService()
              .exceptionHandlers(handlerFunction)
              .build(new TestService());
        }
    };

    @Test
    void testWhenPathPrefixIsNotGivenThenUsesDefault() {
        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(Duration.ofMillis(5000))
                                    .exceptionHandlers((ctx, request, cause) -> HttpResponse.of(400))
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(2);
        final ServiceConfig foo = server.config().serviceConfigs().get(0);
        assertThat(foo.route().paths()).allMatch("/foo"::equals);
        final ServiceConfig bar = server.config().serviceConfigs().get(1);
        assertThat(bar.route().paths()).allMatch("/bar"::equals);
    }

    @Test
    void testWhenPathPrefixIsGivenThenItIsPrefixed() {
        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(Duration.ofMillis(5000))
                                    .exceptionHandlers((ctx, request, cause) -> HttpResponse.of(400))
                                    .pathPrefix("/home")
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(2);
        final ServiceConfig homeFoo = server.config().serviceConfigs().get(0);
        assertThat(homeFoo.route().paths()).allMatch("/home/foo"::equals);
        final ServiceConfig homeBar = server.config().serviceConfigs().get(0);
        assertThat(homeBar.route().paths()).allMatch("/home/foo"::equals);
    }

    @Test
    void testAllConfigurationsAreRespected() {
        final boolean verboseResponse = true;
        final boolean shutdownOnStop = true;
        final long maxRequestLength = 2 * 1024;
        final AccessLogWriter accessLogWriter = AccessLogWriter.common();
        final Duration requestTimeoutDuration = Duration.ofMillis(1000);
        final String defaultServiceName = "TestService";
        final String defaultLogName = "TestLog";

        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(requestTimeoutDuration)
                                    .maxRequestLength(maxRequestLength)
                                    .exceptionHandlers((ctx, request, cause) -> HttpResponse.of(400))
                                    .pathPrefix("/home")
                                    .accessLogWriter(accessLogWriter, shutdownOnStop)
                                    .verboseResponses(verboseResponse)
                                    .defaultServiceName(defaultServiceName)
                                    .defaultLogName(defaultLogName)
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(2);
        final ServiceConfig homeFoo = server.config().serviceConfigs().get(0);
        assertThat(homeFoo.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(homeFoo.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(homeFoo.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(homeFoo.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(homeFoo.verboseResponses()).isTrue();
        final ServiceRequestContext sctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                .build();
        assertThat(homeFoo.defaultServiceNaming().serviceName(sctx)).isEqualTo(defaultServiceName);
        assertThat(homeFoo.defaultLogName()).isEqualTo(defaultLogName);
        final ServiceConfig homeBar = server.config().serviceConfigs().get(1);
        assertThat(homeBar.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(homeBar.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(homeBar.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(homeBar.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(homeBar.verboseResponses()).isTrue();
        assertThat(homeBar.defaultServiceNaming().serviceName(sctx)).isEqualTo(defaultServiceName);
        assertThat(homeBar.defaultLogName()).isEqualTo(defaultLogName);
    }

    @Test
    void testServiceDecoration_shouldCatchException() throws IOException {
        final HttpStatus status = get("/foo").status();

        assertThat(status.code()).isEqualTo(501);
    }

    @Test
    void testGlobalAnnotatedServiceExtensions() {
        final AggregatedHttpResponse result = postJson("/bar", "{\"b\":\"foo\",\"a\":\"bar\"}");

        assertThat(result.contentUtf8()).isEqualTo("{\"a\":\"bar\",\"b\":\"foo\"}");
    }

    private static AggregatedHttpResponse get(String path) {
        final WebClient webClient = WebClient.of(server.httpUri());
        return webClient.get(path).aggregate().join();
    }

    private static AggregatedHttpResponse postJson(String path, String json) {
        final WebClient webClient = WebClient.of(server.httpUri());
        final RequestHeaders postJson = RequestHeaders.of(HttpMethod.POST, path,
                                                          HttpHeaderNames.CONTENT_TYPE, "application/json");
        return webClient.execute(postJson, json).aggregate().join();
    }

    private static class TestService {
        @Get("/foo")
        public HttpResponse foo() {
            throw new RuntimeException();
        }

        @Post("/bar")
        @ProducesJson
        public Map<String, Object> bar(@RequestObject Map<String, Object> map) {
            return map;
        }
    }
}
