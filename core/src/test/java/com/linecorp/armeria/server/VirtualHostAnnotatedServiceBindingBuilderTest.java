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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.assertj.core.util.Files;
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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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
              .annotatedServiceExtensions(ImmutableList.of(),
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
        final String defaultServiceName = "TestService";
        final String defaultLogName = "TestLog";
        final Path multipartUploadsLocation = Files.newTemporaryFolder().toPath();

        final VirtualHost virtualHost = new VirtualHostBuilder(Server.builder(), false)
                .annotatedService()
                .requestTimeout(requestTimeoutDuration)
                .maxRequestLength(maxRequestLength)
                .exceptionHandlers((ctx, request, cause) -> HttpResponse.of(400))
                .pathPrefix("/path")
                .accessLogWriter(accessLogWriter, shutdownOnStop)
                .verboseResponses(verboseResponse)
                .defaultServiceName(defaultServiceName)
                .defaultLogName(defaultLogName)
                .multipartUploadsLocation(multipartUploadsLocation)
                .build(new TestService())
                .build(template);

        assertThat(virtualHost.serviceConfigs()).hasSize(2);
        final ServiceConfig pathBar = virtualHost.serviceConfigs().get(0);
        assertThat(pathBar.route().paths()).allMatch("/path/bar"::equals);
        assertThat(pathBar.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(pathBar.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(pathBar.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(pathBar.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(pathBar.verboseResponses()).isTrue();
        assertThat(pathBar.multipartUploadsLocation()).isSameAs(multipartUploadsLocation);
        final ServiceRequestContext sctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                .build();
        assertThat(pathBar.defaultServiceNaming().serviceName(sctx)).isEqualTo(defaultServiceName);
        assertThat(pathBar.defaultLogName()).isEqualTo(defaultLogName);
        final ServiceConfig pathFoo = virtualHost.serviceConfigs().get(1);
        assertThat(pathFoo.route().paths()).allMatch("/path/foo"::equals);
        assertThat(pathFoo.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(pathFoo.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(pathFoo.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(pathFoo.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(pathFoo.verboseResponses()).isTrue();
        assertThat(pathFoo.defaultServiceNaming().serviceName(sctx)).isEqualTo(defaultServiceName);
        assertThat(pathFoo.defaultLogName()).isEqualTo(defaultLogName);
        assertThat(pathFoo.multipartUploadsLocation()).isSameAs(multipartUploadsLocation);
    }

    @Test
    void testServiceDecoration_shouldCatchException() throws Exception {
        final Endpoint endpoint = Endpoint.of(TEST_HOST, server.httpPort()).withIpAddr("127.0.0.1");
        final WebClient webClientTest = WebClient.of(SessionProtocol.HTTP, endpoint);
        final AggregatedHttpResponse join = webClientTest.get("/path/foo").aggregate().join();

        assertThat(join.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void testGlobalAnnotatedServiceExtensions() {
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
