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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class VirtualHostAnnotatedServiceBindingBuilderTest {

    private static final VirtualHostBuilder template = Server.builder().virtualHostTemplate;
    private static final ExceptionHandlerFunction handlerFunction = (ctx, req, cause) -> HttpResponse.of(501);
    private static final JacksonResponseConverterFunction customJacksonResponseConverterFunction =
            customJacksonResponseConverterFunction();

    private static JacksonResponseConverterFunction customJacksonResponseConverterFunction() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return new JacksonResponseConverterFunction(objectMapper);
    }

    private static final String TEST_HOST = "foo.com";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.virtualHost(TEST_HOST)
              .annotatedHttpServiceExtensions(ImmutableList.of(),
                                              ImmutableList.of(customJacksonResponseConverterFunction),
                                              ImmutableList.of())
              .annotatedService()
              .pathPrefix("/path")
              .exceptionHandlers(handlerFunction)
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
                .exceptionHandlers((ctx, request, cause) -> HttpResponse.of(400))
                .pathPrefix("/path")
                .accessLogWriter(accessLogWriter, shutdownOnStop)
                .contentPreviewerFactory(factory)
                .verboseResponses(verboseResponse)
                .build(new TestService())
                .build(template);

        assertThat(virtualHost.serviceConfigs()).hasSize(2);
        final ServiceConfig serviceConfig = virtualHost.serviceConfigs().get(1);
        assertThat(serviceConfig.route().paths()).allMatch("/path/foo"::equals);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(serviceConfig.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(serviceConfig.requestContentPreviewerFactory()).isEqualTo(factory);
        assertThat(serviceConfig.verboseResponses()).isTrue();
    }

    @Test
    void testServiceDecoration_shouldCatchException() throws Exception {
        final Endpoint endpoint = Endpoint.of(TEST_HOST, server.httpPort()).withIpAddr("127.0.0.1");
        final WebClient webClientTest = WebClient.of(SessionProtocol.HTTP, endpoint);
        final AggregatedHttpResponse join = webClientTest.get("/path/foo").aggregate().join();

        assertThat(join.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void testGlobalAnnotatedHttpServiceExtensions() {
        final AggregatedHttpResponse result =
                postJson("/path/bar", "{\"b\":\"foo\",\"a\":\"bar\"}");

        assertThat(result.contentUtf8()).isEqualTo("{\"a\":\"bar\",\"b\":\"foo\"}");
    }

    private static AggregatedHttpResponse postJson(String path, String json) {
        final Endpoint endpoint = Endpoint.of(TEST_HOST, server.httpPort()).withIpAddr("127.0.0.1");
        final WebClient webClientTest = WebClient.of(SessionProtocol.HTTP, endpoint);
        final RequestHeaders postJson = RequestHeaders.of(HttpMethod.POST, path,
                                                          HttpHeaderNames.CONTENT_TYPE, "application/json");
        return webClientTest.execute(postJson, json).aggregate().join();
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
