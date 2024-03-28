/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.spring;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Collection;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcMeterIdPrefixFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StringRequestConverterFunction;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest.TestConfiguration;

import io.grpc.stub.StreamObserver;
import testing.spring.grpc.Hello.HelloReply;
import testing.spring.grpc.Hello.HelloRequest;
import testing.spring.grpc.TestServiceGrpc;
import testing.spring.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.spring.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.spring.thrift.TestService;
import testing.spring.thrift.TestService.hello_args;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
@Timeout(10)
class ArmeriaAutoConfigurationTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    public static class TestConfiguration {
        @Bean
        public ArmeriaServerConfigurator annotatedService() {
            return sb -> sb.annotatedService()
                           .pathPrefix("/annotated")
                           .defaultServiceName("annotatedService")
                           .decorators(LoggingService.newDecorator())
                           .exceptionHandlers(new IllegalArgumentExceptionHandler())
                           .requestConverters(new StringRequestConverterFunction())
                           .responseConverters(new StringResponseConverter())
                           .build(new AnnotatedService());
        }

        @Bean
        public DocServiceConfigurator annotatedServiceExamples() {
            return dsb -> dsb.exampleHeaders(AnnotatedService.class,
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(AnnotatedService.class, "get",
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(AnnotatedService.class, "error",
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleRequests(AnnotatedService.class, "post", "{\"foo\":\"bar\"}");
        }

        @Bean
        public ArmeriaServerConfigurator helloThriftService() {
            return sb -> sb.route()
                           .path("/thrift")
                           .defaultServiceName("helloThriftService")
                           .decorators(LoggingService.newDecorator())
                           .build(THttpService.of((TestService.Iface) name -> "hello " + name));
        }

        @Bean
        public DocServiceConfigurator helloThriftServiceExamples() {
            return dsb -> dsb.exampleRequests(ImmutableList.of(new hello_args("nameVal")))
                             .exampleHeaders(TestService.class,
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(TestService.class, "hello",
                                             HttpHeaders.of("x-additional-header", "headerVal"));
        }

        @Bean
        public ArmeriaServerConfigurator helloGrpcService() {
            return sb -> sb.route()
                           .defaultServiceName("helloGrpcService")
                           .decorators(LoggingService.newDecorator())
                           .build(GrpcService.builder()
                                             .addService(new HelloGrpcService())
                                             .supportedSerializationFormats(GrpcSerializationFormats.values())
                                             .enableUnframedRequests(true)
                                             .build());
        }

        @Bean
        public DocServiceConfigurator helloGrpcServiceExamples() {
            return dsb -> dsb.exampleRequests(TestServiceGrpc.SERVICE_NAME, "Hello",
                                              HelloRequest.newBuilder().setName("Armeria").build())
                             .exampleHeaders(TestServiceGrpc.SERVICE_NAME,
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(TestServiceGrpc.SERVICE_NAME, "Hello",
                                             HttpHeaders.of("x-additional-header", "headerVal"));
        }

        @Bean
        public MeterIdPrefixFunction myMeterIdPrefixFunction() {
            return GrpcMeterIdPrefixFunction.of("custom.armeria.server");
        }

        @Bean
        public HealthCheckServiceConfigurator healthCheckServiceConfigurator() {
            return builder -> builder.updatable(true);
        }

        @Bean
        public MetricCollectingServiceConfigurator metricCollectingServiceConfigurator() {
            return builder -> builder
                    .successFunction((context, log) -> {
                        final int statusCode = log.responseHeaders().status().code();
                        return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
                    });
        }

        @Bean
        public ServerErrorHandler serverErrorHandler1() {
            return (ctx, cause) -> {
                if (cause instanceof ArithmeticException) {
                    return HttpResponse.of("ArithmeticException was handled by serverErrorHandler!");
                }
                return null;
            };
        }

        @Bean
        public ServerErrorHandler serverErrorHandler2() {
            return (ctx, cause) -> {
                if (cause instanceof IllegalStateException) {
                    return HttpResponse.of("IllegalStateException was handled by serverErrorHandler!");
                }
                return null;
            };
        }
    }

    public static class IllegalArgumentExceptionHandler implements ExceptionHandlerFunction {

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof IllegalArgumentException) {
                return HttpResponse.of("exception");
            }
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    public static class StringResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof String) {
                return HttpResponse.of(HttpStatus.OK,
                                       MediaType.ANY_TEXT_TYPE,
                                       HttpData.ofUtf8(result.toString()),
                                       trailers);
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class AnnotatedService {

        @Get("/get")
        public AggregatedHttpResponse get() {
            return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "annotated");
        }

        @Get("/error")
        public AggregatedHttpResponse error() {
            return AggregatedHttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8, "error");
        }

        // Handles by AnnotatedServiceRegistrationBean#exceptionHandlers
        @Get("/get/2")
        public AggregatedHttpResponse getV2() {
            throw new IllegalArgumentException();
        }

        @Post("/post")
        public JsonNode post(@RequestObject JsonNode jsonNode) {
            return jsonNode;
        }

        @Get("/unhandled1")
        public AggregatedHttpResponse unhandled1() throws Exception {
            throw new ArithmeticException();
        }

        @Get("/unhandled2")
        public AggregatedHttpResponse unhandled2() throws Exception {
            throw new IllegalStateException();
        }

        @Get("/unhandled3")
        public AggregatedHttpResponse unhandled3() throws Exception {
            throw new IllegalAccessException();
        }
    }

    public static class HelloGrpcService extends TestServiceImplBase {
        @Override
        public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            final HelloReply reply = HelloReply.newBuilder()
                                               .setMessage("Hello, " + req.getName())
                                               .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

    @Inject
    private Server server;

    private String newUrl(String scheme) {
        final int port = server.activeLocalPort();
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    void testHttpService() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        final HttpResponse response = client.get("/ok");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }

    @Test
    void testAnnotatedService() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        HttpResponse response = client.get("/annotated/get");

        AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("annotated");

        response = client.get("/annotated/get/2");
        res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("exception");

        final RequestHeaders postJson = RequestHeaders.of(HttpMethod.POST, "/annotated/post",
                                                          HttpHeaderNames.CONTENT_TYPE, "application/json");
        response = client.execute(postJson, "{\"foo\":\"bar\"}");
        res = response.aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("foo").isEqualTo("bar");

        final WebClient webClient = WebClient.of(newUrl("h1c"));
        response = webClient.get("/internal/docs/specification.json");

        res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("services[0].name").isStringEqualTo(
                "com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest$AnnotatedService");
        assertThatJson(res.contentUtf8())
                .node("services[0].methods[3].exampleRequests[0]").isStringEqualTo("{\"foo\":\"bar\"}");
        assertThatJson(res.contentUtf8())
                .node("services[0].exampleHeaders[0].x-additional-header").isStringEqualTo("headerVal");
        assertThatJson(res.contentUtf8())
                .node("services[0].methods[0].exampleHeaders[0].x-additional-header")
                .isStringEqualTo("headerVal");
    }

    @Test
    void testThriftService() throws Exception {
        final TestService.Iface client = ThriftClients.newClient(newUrl("h1c") + "/thrift",
                                                                 TestService.Iface.class);
        assertThat(client.hello("world")).isEqualTo("hello world");

        final WebClient webClient = WebClient.of(newUrl("h1c"));
        final HttpResponse response = webClient.get("/internal/docs/specification.json");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("services[2].name").isStringEqualTo(
                "testing.spring.thrift.TestService");
        assertThatJson(res.contentUtf8())
                .node("services[2].exampleHeaders[0].x-additional-header").isStringEqualTo("headerVal");
        assertThatJson(res.contentUtf8())
                .node("services[0].methods[0].exampleHeaders[0].x-additional-header")
                .isStringEqualTo("headerVal");
    }

    @Test
    void testGrpcService() throws Exception {
        final TestServiceBlockingStub client = GrpcClients.newClient(newUrl("h2c") + '/',
                                                                     TestServiceBlockingStub.class);
        final HelloRequest request = HelloRequest.newBuilder()
                                                 .setName("world")
                                                 .build();
        assertThat(client.hello(request).getMessage()).isEqualTo("Hello, world");

        final WebClient webClient = WebClient.of(newUrl("h1c"));
        final HttpResponse response = webClient.get("/internal/docs/specification.json");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("services[1].name").isStringEqualTo(
                "testing.spring.grpc.TestService");
        assertThatJson(res.contentUtf8())
                .node("services[1].exampleHeaders[0].x-additional-header").isStringEqualTo("headerVal");
        assertThatJson(res.contentUtf8())
                .node("services[1].methods[0].exampleHeaders[0].x-additional-header")
                .isStringEqualTo("headerVal");
    }

    @Test
    void testPortConfiguration() {
        final Collection<ServerPort> ports = server.activePorts().values();
        assertThat(ports.stream().filter(ServerPort::hasHttp)).hasSize(3);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isAnyLocalAddress())).hasSize(2);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isLoopbackAddress())).hasSize(1);
    }

    @Test
    void testMetrics() {
        assertThat(GrpcClients.newClient(newUrl("h2c") + '/', TestServiceBlockingStub.class)
                              .hello(HelloRequest.getDefaultInstance())
                              .getMessage()).isNotNull();

        final String metricReport = WebClient.of(newUrl("http"))
                                             .get("/internal/metrics")
                                             .aggregate().join()
                                             .contentUtf8();
        assertThat(metricReport).contains("# TYPE custom_armeria_server_response_duration_seconds_max gauge");
        assertThat(metricReport).contains(
                "custom_armeria_server_response_duration_seconds_max{grpc_status=\"0\"");
    }

    @Test
    void testCustomSuccessMetrics() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));
        final HttpResponse response = client.get("/annotated/error");
        final String expectedSuccess =
                "http_status=\"404\",method=\"error\",result=\"success\",service=\"annotatedService\",} 1.0";
        final String expectedFailure =
                "http_status=\"404\",method=\"error\",result=\"failure\",service=\"annotatedService\",} 0.0";

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.contentUtf8()).isEqualTo("error");

        await().pollInSameThread()
               .untilAsserted(() -> {
                   final String metricReport = WebClient.of(newUrl("http"))
                                                        .get("/internal/metrics")
                                                        .aggregate()
                                                        .join()
                                                        .contentUtf8();
                   assertThat(metricReport).contains(expectedSuccess);
                   assertThat(metricReport).contains(expectedFailure);
               });
    }

    @Test
    void testHealthCheckService() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        HttpResponse response = client.get("/internal/healthcheck");
        AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        response = client.post("/internal/healthcheck", "{\"healthy\":false}");
        res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * When a ServerErrorHandler @Bean is present,
     * Server.config().errorHandler() does not register a DefaultServerErrorHandler.
     * Since DefaultServerErrorHandler is not public, test were forced to compare toString.
     * Needs to be improved.
     */
    @Test
    void testServerErrorHandlerRegistration() {
        assertThat(server.config().errorHandler().toString()).isNotEqualTo("INSTANCE");
    }

    @Test
    void testServerErrorHandler() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        // ArithmeticException will be handled by serverErrorHandler
        final HttpResponse response1 = client.get("/annotated/unhandled1");
        final AggregatedHttpResponse res1 = response1.aggregate().join();
        assertThat(res1.status()).isEqualTo(HttpStatus.OK);
        assertThat(res1.contentUtf8()).isEqualTo("ArithmeticException was handled by serverErrorHandler!");

        // IllegalStateException will be handled by serverErrorHandler
        final HttpResponse response2 = client.get("/annotated/unhandled2");
        final AggregatedHttpResponse res2 = response2.aggregate().join();
        assertThat(res2.status()).isEqualTo(HttpStatus.OK);
        assertThat(res2.contentUtf8()).isEqualTo("IllegalStateException was handled by serverErrorHandler!");

        // IllegalAccessException will be handled by DefaultServerErrorHandler which is used as the
        // final fallback when all customized handlers return null
        final HttpResponse response3 = client.get("/annotated/unhandled3");
        final AggregatedHttpResponse res3 = response3.aggregate().join();
        assertThat(res3.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
