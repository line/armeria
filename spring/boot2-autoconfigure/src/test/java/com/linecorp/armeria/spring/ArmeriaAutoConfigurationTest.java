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
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
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
import com.linecorp.armeria.common.grpc.GrpcMeterIdPrefixFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
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
import com.linecorp.armeria.spring.test.grpc.main.Hello.HelloReply;
import com.linecorp.armeria.spring.test.grpc.main.Hello.HelloRequest;
import com.linecorp.armeria.spring.test.grpc.main.HelloServiceGrpc;
import com.linecorp.armeria.spring.test.grpc.main.HelloServiceGrpc.HelloServiceBlockingStub;
import com.linecorp.armeria.spring.test.grpc.main.HelloServiceGrpc.HelloServiceImplBase;
import com.linecorp.armeria.spring.test.thrift.main.HelloService;
import com.linecorp.armeria.spring.test.thrift.main.HelloService.hello_args;

import io.grpc.stub.StreamObserver;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class, properties =
        "management.metrics.export.defaults.enabled=true") // @AutoConfigureMetrics is not allowed for boot1.
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class ArmeriaAutoConfigurationTest {

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
                           .build(THttpService.of((HelloService.Iface) name -> "hello " + name));
        }

        @Bean
        public DocServiceConfigurator helloThriftServiceExamples() {
            return dsb -> dsb.exampleRequests(ImmutableList.of(new hello_args("nameVal")))
                             .exampleHeaders(HelloService.class,
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(HelloService.class, "hello",
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
            return dsb -> dsb.exampleRequests(HelloServiceGrpc.SERVICE_NAME, "Hello",
                                              HelloRequest.newBuilder().setName("Armeria").build())
                             .exampleHeaders(HelloServiceGrpc.SERVICE_NAME,
                                             HttpHeaders.of("x-additional-header", "headerVal"))
                             .exampleHeaders(HelloServiceGrpc.SERVICE_NAME, "Hello",
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
        public MetricCollectingConfigurator metricCollectingConfigurator() {
            return builder -> builder
                    .successFunction((context, log) -> {
                        final int statusCode = log.responseHeaders().status().code();
                        return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
                    });
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
    }

    public static class HelloGrpcService extends HelloServiceImplBase {
        @Override
        public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            final HelloReply reply = HelloReply.newBuilder()
                                               .setMessage("Hello, " + req.getName())
                                               .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Inject
    private Server server;

    private String newUrl(String scheme) {
        final int port = server.activeLocalPort();
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testHttpService() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        final HttpResponse response = client.get("/ok");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }

    @Test
    public void testAnnotatedService() throws Exception {
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
    public void testThriftService() throws Exception {
        final HelloService.Iface client = Clients.newClient(newUrl("tbinary+h1c") + "/thrift",
                                                            HelloService.Iface.class);
        assertThat(client.hello("world")).isEqualTo("hello world");

        final WebClient webClient = WebClient.of(newUrl("h1c"));
        final HttpResponse response = webClient.get("/internal/docs/specification.json");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("services[2].name").isStringEqualTo(
                "com.linecorp.armeria.spring.test.thrift.main.HelloService");
        assertThatJson(res.contentUtf8())
                .node("services[2].exampleHeaders[0].x-additional-header").isStringEqualTo("headerVal");
        assertThatJson(res.contentUtf8())
                .node("services[0].methods[0].exampleHeaders[0].x-additional-header")
                .isStringEqualTo("headerVal");
    }

    @Test
    public void testGrpcService() throws Exception {
        final HelloServiceBlockingStub client = Clients.newClient(newUrl("gproto+h2c") + '/',
                                                                  HelloServiceBlockingStub.class);
        final HelloRequest request = HelloRequest.newBuilder()
                                                 .setName("world")
                                                 .build();
        assertThat(client.hello(request).getMessage()).isEqualTo("Hello, world");

        final WebClient webClient = WebClient.of(newUrl("h1c"));
        final HttpResponse response = webClient.get("/internal/docs/specification.json");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("services[1].name").isStringEqualTo(
                "com.linecorp.armeria.spring.test.grpc.main.HelloService");
        assertThatJson(res.contentUtf8())
                .node("services[1].exampleHeaders[0].x-additional-header").isStringEqualTo("headerVal");
        assertThatJson(res.contentUtf8())
                .node("services[1].methods[0].exampleHeaders[0].x-additional-header")
                .isStringEqualTo("headerVal");
    }

    @Test
    public void testPortConfiguration() {
        final Collection<ServerPort> ports = server.activePorts().values();
        assertThat(ports.stream().filter(ServerPort::hasHttp)).hasSize(3);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isAnyLocalAddress())).hasSize(2);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isLoopbackAddress())).hasSize(1);
    }

    @Test
    public void testMetrics() {
        Clients.newClient(newUrl("gproto+h2c") + '/', HelloServiceBlockingStub.class)
               .hello(HelloRequest.getDefaultInstance())
               .getMessage();

        final String metricReport = WebClient.of(newUrl("http"))
                                             .get("/internal/metrics")
                                             .aggregate().join()
                                             .contentUtf8();
        assertThat(metricReport).contains("# TYPE custom_armeria_server_response_duration_seconds_max gauge");
        assertThat(metricReport).contains(
                "custom_armeria_server_response_duration_seconds_max{grpc_status=\"0\"");
    }

    @Test
    public void testCustomSuccessMetrics() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        final HttpResponse response = client.get("/annotated/error");

        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.contentUtf8()).isEqualTo("error");

        final String metricReport = WebClient.of(newUrl("http"))
                                             .get("/internal/metrics")
                                             .aggregate()
                                             .join()
                                             .contentUtf8();
        final String expectedSuccess =
                "http_status=\"404\",method=\"error\",result=\"success\",service=\"annotatedService\",} 1.0";
        final String expectedFailure =
                "http_status=\"404\",method=\"error\",result=\"failure\",service=\"annotatedService\",} 0.0";

        await().atMost(20, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(metricReport).contains(expectedSuccess);
                   assertThat(metricReport).contains(expectedFailure);
               });
    }

    @Test
    public void testHealthCheckService() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));

        HttpResponse response = client.get("/internal/healthcheck");
        AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        response = client.post("/internal/healthcheck", "{\"healthy\":false}");
        res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
